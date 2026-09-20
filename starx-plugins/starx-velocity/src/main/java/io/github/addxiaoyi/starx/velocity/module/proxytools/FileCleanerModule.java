/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.module.proxytools;

import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class FileCleanerModule
implements VelocityModule {
    private final StarxVelocityPlugin plugin;
    private final Config config;
    private final File basePath;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicInteger filesDeleted = new AtomicInteger(0);
    private double mbSaved;

    public FileCleanerModule(StarxVelocityPlugin plugin, Config config) {
        this(plugin, config, new File(".").getAbsoluteFile());
    }

    FileCleanerModule(StarxVelocityPlugin plugin, Config config, File basePath) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.basePath = Objects.requireNonNull(basePath, "basePath");
    }

    @Override
    public String name() {
        return "starx.proxytools.filecleaner";
    }

    @Override
    public void onEnable() {
        if (!this.config.enabled()) {
            return;
        }
        this.initialized.set(true);
    }

    @Override
    public void onDisable() {
        this.initialized.set(false);
    }

    public boolean isInitialized() {
        return this.initialized.get();
    }

    public int getFilesDeleted() {
        return this.filesDeleted.get();
    }

    public double getMbSaved() {
        return this.mbSaved;
    }

    public void runCleanup() {
        this.filesDeleted.set(0);
        this.mbSaved = 0.0;
        for (FolderConfig folderCfg : this.config.folders()) {
            this.scanFilesInDir(folderCfg);
        }
        for (FileConfig fileCfg : this.config.files()) {
            this.scanFile(fileCfg);
        }
    }

    private void scanFilesInDir(FolderConfig folderCfg) {
        long size;
        int count;
        int age;
        File[] files;
        String loc = folderCfg.location();
        File folder = new File(loc);
        if (!folder.isAbsolute()) {
            folder = new File(this.basePath, loc);
        }
        if ((files = folder.listFiles()) == null) {
            this.plugin.logger().log(Level.SEVERE, "Could not find folder: " + folder.getPath());
            return;
        }
        ArrayList<File> fileList = new ArrayList<File>(Arrays.asList(files));
        if (fileList.isEmpty()) {
            return;
        }
        List<String> excluded = folderCfg.exclude();
        if (excluded != null && !excluded.isEmpty()) {
            this.filterExcludedFiles(fileList, excluded);
        }
        if ((age = folderCfg.age()) > -1) {
            this.deleteByAge(fileList, age);
        }
        if ((count = folderCfg.count()) > -1) {
            this.deleteByCount(fileList, count);
        }
        if ((size = folderCfg.size()) > -1L) {
            this.deleteBySize(fileList, size);
        }
    }

    private void filterExcludedFiles(List<File> fileList, List<String> excludedRules) {
        ArrayList<File> toRemove = new ArrayList<File>();
        HashMap<String, Pattern> compiledPatterns = new HashMap<String, Pattern>();
        HashSet<String> invalidPatterns = new HashSet<String>();
        block2: for (File file : fileList) {
            for (String rule : excludedRules) {
                if (rule.equals(file.getName())) {
                    toRemove.add(file);
                    continue block2;
                }
                Pattern pattern = (Pattern)compiledPatterns.get(rule);
                if (pattern == null && !invalidPatterns.contains(rule)) {
                    try {
                        pattern = Pattern.compile(rule);
                        compiledPatterns.put(rule, pattern);
                    }
                    catch (PatternSyntaxException e) {
                        invalidPatterns.add(rule);
                        this.plugin.logger().log(Level.SEVERE, "Invalid regex: " + rule);
                    }
                }
                if (pattern == null || !pattern.matcher(file.getName()).matches()) continue;
                toRemove.add(file);
                continue block2;
            }
        }
        fileList.removeAll(toRemove);
    }

    private void deleteByAge(List<File> fileList, int age) {
        ArrayList<File> toRemove = new ArrayList<File>();
        long cutoff = (long)age * 24L * 60L * 60L * 1000L;
        long now = System.currentTimeMillis();
        for (File file : fileList) {
            if (now - file.lastModified() <= cutoff) continue;
            this.deleteFile(file);
            toRemove.add(file);
        }
        fileList.removeAll(toRemove);
    }

    private void deleteByCount(List<File> fileList, int count) {
        ArrayList<File> toRemove = new ArrayList<File>();
        fileList.sort(Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < fileList.size() - count; ++i) {
            this.deleteFile(fileList.get(i));
            toRemove.add(fileList.get(i));
        }
        fileList.removeAll(toRemove);
    }

    private void deleteBySize(List<File> fileList, long sizeLimit) {
        fileList.sort(Comparator.comparingLong(File::length));
        for (File file : fileList) {
            if (!((double)Math.round((double)file.length() / 1024.0) > (double)sizeLimit)) continue;
            this.deleteFile(file);
        }
    }

    private void scanFile(FileConfig fileCfg) {
        long size;
        String loc = fileCfg.location();
        File file = new File(loc);
        if (!file.isAbsolute()) {
            file = new File(this.basePath, loc);
        }
        if (!file.exists()) {
            return;
        }
        int age = fileCfg.age();
        if (age > -1) {
            long cutoff = (long)age * 24L * 60L * 60L * 1000L;
            if (System.currentTimeMillis() - file.lastModified() > cutoff) {
                this.deleteFile(file);
                return;
            }
        }
        if ((size = fileCfg.size()) > -1L && (double)Math.round((double)file.length() / 1024.0) > (double)size) {
            this.deleteFile(file);
        }
    }

    private void deleteFile(File file) {
        if (!file.isFile()) {
            return;
        }
        long fileSize = file.length();
        if (file.delete()) {
            this.filesDeleted.incrementAndGet();
            this.mbSaved += (double)Math.round((double)fileSize / 1048576.0 * 100.0) / 100.0;
            this.plugin.logger().info("Deleted file: " + file.getPath());
        } else {
            this.plugin.logger().log(Level.SEVERE, "Could not delete file: " + file.getPath());
        }
    }

    public static interface Config {
        public boolean enabled();

        public String schedule();

        public List<FolderConfig> folders();

        public List<FileConfig> files();

        public static Config defaultConfig() {
            return new Config(){

                @Override
                public boolean enabled() {
                    return false;
                }

                @Override
                public String schedule() {
                    return "0 0 * * *";
                }

                @Override
                public List<FolderConfig> folders() {
                    return List.of();
                }

                @Override
                public List<FileConfig> files() {
                    return List.of();
                }
            };
        }
    }

    public static interface FolderConfig {
        public String location();

        public int age();

        public int count();

        public long size();

        public List<String> exclude();
    }

    public static interface FileConfig {
        public String location();

        public int age();

        public long size();
    }
}
