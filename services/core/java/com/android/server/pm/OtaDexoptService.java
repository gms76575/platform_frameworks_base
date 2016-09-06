/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.pm;

import static com.android.server.pm.Installer.DEXOPT_OTA;
import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;
import static com.android.server.pm.InstructionSets.getDexCodeInstructionSets;
import static com.android.server.pm.PackageManagerServiceCompilerMapping.getCompilerFilterForReason;

import android.content.Context;
import android.content.pm.IOtaDexopt;
import android.content.pm.PackageParser;
import android.os.Environment;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.storage.StorageManager;
import android.util.Log;
import android.util.Slog;

import com.android.internal.os.InstallerConnection;
import com.android.internal.os.InstallerConnection.InstallerException;

import java.io.File;
import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A service for A/B OTA dexopting.
 *
 * {@hide}
 */
public class OtaDexoptService extends IOtaDexopt.Stub {
    private final static String TAG = "OTADexopt";
    private final static boolean DEBUG_DEXOPT = true;

    private final Context mContext;
    private final PackageManagerService mPackageManagerService;

    // TODO: Evaluate the need for WeakReferences here.

    /**
     * The list of packages to dexopt.
     */
    private List<PackageParser.Package> mDexoptPackages;

    /**
     * The list of dexopt invocations for the current package (which will no longer be in
     * mDexoptPackages). This can be more than one as a package may have multiple code paths,
     * e.g., in the split-APK case.
     */
    private List<String> mCommandsForCurrentPackage;

    private int completeSize;

    public OtaDexoptService(Context context, PackageManagerService packageManagerService) {
        this.mContext = context;
        this.mPackageManagerService = packageManagerService;

        // Now it's time to check whether we need to move any A/B artifacts.
        moveAbArtifacts(packageManagerService.mInstaller);
    }

    public static OtaDexoptService main(Context context,
            PackageManagerService packageManagerService) {
        OtaDexoptService ota = new OtaDexoptService(context, packageManagerService);
        ServiceManager.addService("otadexopt", ota);

        return ota;
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ResultReceiver resultReceiver) throws RemoteException {
        (new OtaDexoptShellCommand(this)).exec(
                this, in, out, err, args, resultReceiver);
    }

    @Override
    public synchronized void prepare() throws RemoteException {
        if (mDexoptPackages != null) {
            throw new IllegalStateException("already called prepare()");
        }
        synchronized (mPackageManagerService.mPackages) {
            mDexoptPackages = PackageManagerServiceUtils.getPackagesForDexopt(
                    mPackageManagerService.mPackages.values(), mPackageManagerService);
        }
        completeSize = mDexoptPackages.size();
        mCommandsForCurrentPackage = null;
    }

    @Override
    public synchronized void cleanup() throws RemoteException {
        if (DEBUG_DEXOPT) {
            Log.i(TAG, "Cleaning up OTA Dexopt state.");
        }
        mDexoptPackages = null;
        mCommandsForCurrentPackage = null;
    }

    @Override
    public synchronized boolean isDone() throws RemoteException {
        if (mDexoptPackages == null) {
            throw new IllegalStateException("done() called before prepare()");
        }

        return mDexoptPackages.isEmpty() && (mCommandsForCurrentPackage == null);
    }

    @Override
    public synchronized float getProgress() throws RemoteException {
        // We approximate by number of packages here. We could track all compiles, if we
        // generated them ahead of time. Right now we're trying to conserve memory.
        if (completeSize == 0) {
            return 1f;
        }
        int packagesLeft = mDexoptPackages.size() + (mCommandsForCurrentPackage != null ? 1 : 0);
        return (completeSize - packagesLeft) / ((float)completeSize);
    }

    /**
     * Return the next dexopt command for the current package. Enforces the invariant
     */
    private String getNextPackageDexopt() {
        if (mCommandsForCurrentPackage != null) {
            String next = mCommandsForCurrentPackage.remove(0);
            if (mCommandsForCurrentPackage.isEmpty()) {
                mCommandsForCurrentPackage = null;
            }
            return next;
        }
        return null;
    }

    @Override
    public synchronized String nextDexoptCommand() throws RemoteException {
        if (mDexoptPackages == null) {
            throw new IllegalStateException("dexoptNextPackage() called before prepare()");
        }

        // Get the next command.
        for (;;) {
            // Check whether there's one for the current package.
            String next = getNextPackageDexopt();
            if (next != null) {
                return next;
            }

            // Move to the next package, if possible.
            if (mDexoptPackages.isEmpty()) {
                return "Nothing to do";
            }

            PackageParser.Package nextPackage = mDexoptPackages.remove(0);

            if (DEBUG_DEXOPT) {
                Log.i(TAG, "Processing " + nextPackage.packageName + " for OTA dexopt.");
            }

            // Generate the next mPackageDexopts state. Ignore errors, this loop is strongly
            // monotonically increasing, anyways.
            generatePackageDexopts(nextPackage);

            // Invariant check: mPackageDexopts is null or not empty.
            if (mCommandsForCurrentPackage != null && mCommandsForCurrentPackage.isEmpty()) {
                cleanup();
                throw new IllegalStateException("mPackageDexopts empty for " + nextPackage);
            }
        }
    }

    /**
     * Generate all dexopt commands for the given package and place them into mPackageDexopts.
     * Returns true on success, false in an error situation like low disk space.
     */
    private synchronized boolean generatePackageDexopts(PackageParser.Package nextPackage) {
        // Check for low space.
        // TODO: If apps are not installed in the internal /data partition, we should compare
        //       against that storage's free capacity.
        File dataDir = Environment.getDataDirectory();
        @SuppressWarnings("deprecation")
        long lowThreshold = StorageManager.from(mContext).getStorageLowBytes(dataDir);
        if (lowThreshold == 0) {
            throw new IllegalStateException("Invalid low memory threshold");
        }
        long usableSpace = dataDir.getUsableSpace();
        if (usableSpace < lowThreshold) {
            Log.w(TAG, "Not running dexopt on " + nextPackage.packageName + " due to low memory: " +
                    usableSpace);
            return false;
        }

        // Use our custom connection that just collects the commands.
        RecordingInstallerConnection collectingConnection = new RecordingInstallerConnection();
        Installer collectingInstaller = new Installer(mContext, collectingConnection);

        // Use the package manager install and install lock here for the OTA dex optimizer.
        PackageDexOptimizer optimizer = new OTADexoptPackageDexOptimizer(
                collectingInstaller, mPackageManagerService.mInstallLock, mContext);
        optimizer.performDexOpt(nextPackage, nextPackage.usesLibraryFiles,
                null /* ISAs */, false /* checkProfiles */,
                getCompilerFilterForReason(PackageManagerService.REASON_AB_OTA));

        mCommandsForCurrentPackage = collectingConnection.commands;
        if (mCommandsForCurrentPackage.isEmpty()) {
            mCommandsForCurrentPackage = null;
        }

        return true;
    }

    @Override
    public synchronized void dexoptNextPackage() throws RemoteException {
        if (mDexoptPackages == null) {
            throw new IllegalStateException("dexoptNextPackage() called before prepare()");
        }
        if (mDexoptPackages.isEmpty()) {
            // Tolerate repeated calls.
            return;
        }

        PackageParser.Package nextPackage = mDexoptPackages.remove(0);

        if (DEBUG_DEXOPT) {
            Log.i(TAG, "Processing " + nextPackage.packageName + " for OTA dexopt.");
        }

        // Check for low space.
        // TODO: If apps are not installed in the internal /data partition, we should compare
        //       against that storage's free capacity.
        File dataDir = Environment.getDataDirectory();
        @SuppressWarnings("deprecation")
        long lowThreshold = StorageManager.from(mContext).getStorageLowBytes(dataDir);
        if (lowThreshold == 0) {
            throw new IllegalStateException("Invalid low memory threshold");
        }
        long usableSpace = dataDir.getUsableSpace();
        if (usableSpace < lowThreshold) {
            Log.w(TAG, "Not running dexopt on " + nextPackage.packageName + " due to low memory: " +
                    usableSpace);
            return;
        }

        PackageDexOptimizer optimizer = new OTADexoptPackageDexOptimizer(
                mPackageManagerService.mInstaller, mPackageManagerService.mInstallLock, mContext);
        optimizer.performDexOpt(nextPackage, nextPackage.usesLibraryFiles, null /* ISAs */,
                false /* checkProfiles */,
                getCompilerFilterForReason(PackageManagerService.REASON_AB_OTA));
    }

    private void moveAbArtifacts(Installer installer) {
        if (mDexoptPackages != null) {
            throw new IllegalStateException("Should not be ota-dexopting when trying to move.");
        }

        // Look into all packages.
        Collection<PackageParser.Package> pkgs = mPackageManagerService.getPackages();
        for (PackageParser.Package pkg : pkgs) {
            if (pkg == null) {
                continue;
            }

            // Does the package have code? If not, there won't be any artifacts.
            if (!PackageDexOptimizer.canOptimizePackage(pkg)) {
                continue;
            }
            if (pkg.codePath == null) {
                Slog.w(TAG, "Package " + pkg + " can be optimized but has null codePath");
                continue;
            }

            // If the path is in /system or /vendor, ignore. It will have been ota-dexopted into
            // /data/ota and moved into the dalvik-cache already.
            if (pkg.codePath.startsWith("/system") || pkg.codePath.startsWith("/vendor")) {
                continue;
            }

            final String[] instructionSets = getAppDexInstructionSets(pkg.applicationInfo);
            final List<String> paths = pkg.getAllCodePathsExcludingResourceOnly();
            final String[] dexCodeInstructionSets = getDexCodeInstructionSets(instructionSets);
            for (String dexCodeInstructionSet : dexCodeInstructionSets) {
                for (String path : paths) {
                    String oatDir = PackageDexOptimizer.getOatDir(new File(pkg.codePath)).
                            getAbsolutePath();

                    // TODO: Check first whether there is an artifact, to save the roundtrip time.

                    try {
                        installer.moveAb(path, dexCodeInstructionSet, oatDir);
                    } catch (InstallerException e) {
                    }
                }
            }
        }
    }

    private static class OTADexoptPackageDexOptimizer extends
            PackageDexOptimizer.ForcedUpdatePackageDexOptimizer {

        public OTADexoptPackageDexOptimizer(Installer installer, Object installLock,
                Context context) {
            super(installer, installLock, context, "*otadexopt*");
        }

        @Override
        protected int adjustDexoptFlags(int dexoptFlags) {
            // Add the OTA flag.
            return dexoptFlags | DEXOPT_OTA;
        }

    }

    private static class RecordingInstallerConnection extends InstallerConnection {
        public List<String> commands = new ArrayList<String>(1);

        @Override
        public void setWarnIfHeld(Object warnIfHeld) {
            throw new IllegalStateException("Should not reach here");
        }

        @Override
        public synchronized String transact(String cmd) {
            commands.add(cmd);
            return "0";
        }

        @Override
        public boolean mergeProfiles(int uid, String pkgName) throws InstallerException {
            throw new IllegalStateException("Should not reach here");
        }

        @Override
        public boolean dumpProfiles(String gid, String packageName, String codePaths)
                throws InstallerException {
            throw new IllegalStateException("Should not reach here");
        }

        @Override
        public void disconnect() {
            throw new IllegalStateException("Should not reach here");
        }

        @Override
        public void waitForConnection() {
            throw new IllegalStateException("Should not reach here");
        }
    }
}
