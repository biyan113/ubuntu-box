package dev.ubox.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/** Ubuntu rootfs 的目录布局、首次解压和每次启动前的修正。 */
final class Rootfs {
  static final String ASSET = "rootfs.tar.gz.bin";
  private static final String MARKER = ".ubox-installed";

  final File base, rootfs, lib, tmp;

  Rootfs(Context context) {
    base = new File(context.getFilesDir(), "ubox");
    rootfs = new File(base, "rootfs");
    lib = new File(base, "lib");
    tmp = new File(base, "tmp");
  }

  boolean isInstalled() {
    return new File(rootfs, MARKER).isFile() && new File(rootfs, "bin/bash").exists();
  }

  interface Progress {
    void update(String stage, long done, long total);
  }

  /** 解压到临时目录，全部完成后再替换，中途失败不会留下半套环境。 */
  void install(Context context, Progress progress) throws IOException {
    File staging = new File(base, "rootfs.staging");
    deleteRecursively(staging);
    if (!staging.mkdirs()) throw new IOException("无法创建 " + staging);

    long total;
    try (AssetFileDescriptor fd = context.getAssets().openFd(ASSET)) {
      total = fd.getLength();
    }
    List<String[]> hardlinks = new ArrayList<>();
    List<Object[]> directoryModes = new ArrayList<>();
    try (CountingInputStream counted =
            new CountingInputStream(new BufferedInputStream(context.getAssets().open(ASSET), 1 << 16));
        TarArchiveInputStream tar =
            new TarArchiveInputStream(new GzipCompressorInputStream(counted))) {
      byte[] buffer = new byte[1 << 16];
      long lastReport = 0;
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        String name = normalize(entry.getName());
        if (name == null) continue;
        File target = new File(staging, name);
        if (entry.isDirectory()) {
          mkdirs(target);
          directoryModes.add(new Object[] {target, entry.getMode()});
        } else if (entry.isSymbolicLink()) {
          mkdirs(target.getParentFile());
          target.delete();
          symlink(entry.getLinkName(), target);
        } else if (entry.isLink()) {
          String linked = normalize(entry.getLinkName());
          if (linked != null) hardlinks.add(new String[] {linked, name});
        } else if (entry.isFile()) {
          mkdirs(target.getParentFile());
          target.delete();
          try (OutputStream out = new FileOutputStream(target)) {
            int n;
            while ((n = tar.read(buffer)) != -1) out.write(buffer, 0, n);
          }
          chmod(target, (entry.getMode() & 0777) | 0600);
        }
        // 设备节点、FIFO 在 proot 里由宿主 /dev 提供，直接跳过
        if (counted.count - lastReport > 256 * 1024) {
          lastReport = counted.count;
          progress.update("正在解压 Ubuntu…", counted.count, total);
        }
      }
    }
    progress.update("正在配置环境…", total, total);
    for (String[] link : hardlinks) {
      File source = new File(staging, link[0]);
      File target = new File(staging, link[1]);
      mkdirs(target.getParentFile());
      target.delete();
      try {
        Os.link(source.getAbsolutePath(), target.getAbsolutePath());
      } catch (ErrnoException denied) {
        copyFile(source, target);
      }
    }
    for (Object[] directory : directoryModes)
      chmod((File) directory[0], ((Integer) directory[1] & 07777) | 0700);

    configure(staging);
    write(new File(staging, MARKER), readAsset(context, "rootfs.version"));

    deleteRecursively(rootfs);
    if (!staging.renameTo(rootfs)) throw new IOException("无法启用新的 rootfs");
  }

  /** 只在首次安装时写入，之后归用户管理。 */
  private void configure(File root) throws IOException {
    File resolv = new File(root, "etc/resolv.conf");
    resolv.delete(); // 原本是指向 systemd-resolved 的符号链接
    write(resolv, "nameserver 223.5.5.5\nnameserver 8.8.8.8\nnameserver 1.1.1.1\n");
    write(
        new File(root, "etc/hosts"),
        "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n");
    // proot 里没有真正的 setgroups/setuid，apt 降权到 _apt 会失败
    write(
        new File(root, "etc/apt/apt.conf.d/99ubox"),
        "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\n");
    File tmpDir = new File(root, "tmp");
    mkdirs(tmpDir);
    chmod(tmpDir, 01777);
  }

  /** 把应用进程的补充组写进 /etc/group，否则 bash 会反复提示 cannot find name for group ID。 */
  private static void addAndroidGroups(File root) throws IOException {
    Set<String> gids = new LinkedHashSet<>();
    try (InputStream in = new FileInputStream("/proc/self/status")) {
      for (String line : new String(Io.readAll(in), StandardCharsets.UTF_8).split("\n"))
        if (line.startsWith("Groups:"))
          for (String gid : line.substring(7).trim().split("\\s+")) if (!gid.isEmpty()) gids.add(gid);
    }
    gids.add(String.valueOf(android.os.Process.myUid() % 100000));
    StringBuilder group = new StringBuilder(), gshadow = new StringBuilder();
    Set<String> known = new java.util.HashSet<>();
    for (String line : read(new File(root, "etc/group")).split("\n")) {
      String[] fields = line.split(":", -1);
      if (fields.length > 2) known.add(fields[2]);
    }
    for (String gid : gids) {
      if (known.contains(gid)) continue;
      group.append("aid_").append(gid).append(":x:").append(gid).append(":root\n");
      gshadow.append("aid_").append(gid).append(":*::root\n");
    }
    append(new File(root, "etc/group"), group.toString());
    append(new File(root, "etc/gshadow"), gshadow.toString());
  }

  /** 每次启动会话前执行：刷新随 APK 更新的脚本和兼容文件。 */
  void refreshGuestFiles(Context context) throws IOException {
    mkdirs(lib);
    mkdirs(tmp);
    File local = new File(rootfs, "usr/local/bin");
    writeAsset(context, "guest/ubox-mirror", new File(local, "ubox-mirror"), 0755);
    writeAsset(context, "guest/ubox-node", new File(local, "ubox-node"), 0755);
    writeAsset(context, "guest/ubox-install", new File(local, "ubox-install"), 0755);
    writeAsset(context, "guest/ubox-sshd", new File(local, "ubox-sshd"), 0755);
    if (new File("/system/bin/ping").exists())
      writeAsset(context, "guest/ping", new File(local, "ping"), 0755);
    writeAsset(
        context, "guest/node-compat.cjs", new File(rootfs, "usr/local/lib/ubox/node-compat.cjs"), 0644);
    writeAsset(context, "guest/profile.sh", new File(rootfs, "etc/profile.d/ubox.sh"), 0644);
    writeAsset(context, "guest/motd", new File(rootfs, "etc/ubox-motd"), 0644);
    // 授权（如存储）后应用会获得新的补充组，每次都补齐
    addAndroidGroups(rootfs);
    writeFakeProc();
  }

  File fakeProc(String name) {
    return new File(base, "proc/" + name);
  }

  /** Android 8+ 不允许应用读取这些文件，读不到时用假数据绑定过去（与 proot-distro 做法一致）。 */
  private void writeFakeProc() throws IOException {
    write(fakeProc("loadavg"), "0.12 0.07 0.02 2/165 765\n");
    write(
        fakeProc("stat"),
        "cpu  1957 0 2877 93280 262 342 254 87 0 0\n"
            + "cpu0 31 0 226 12027 82 10 4 9 0 0\n"
            + "intr 63361 0 0 0 0 0 0 0 0 0\nctxt 38014093\nbtime 1694292441\n"
            + "processes 26442\nprocs_running 1\nprocs_blocked 0\nsoftirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n");
    write(fakeProc("uptime"), "124.08 932.80\n");
    write(fakeProc("version"), "Linux version 6.1.0-ubox (proot) #1 SMP PREEMPT\n");
    write(fakeProc("vmstat"), "nr_free_pages 1743136\nnr_zone_inactive_anon 179281\n");
  }

  void delete() throws IOException {
    deleteRecursively(new File(base, "rootfs.staging"));
    deleteRecursively(rootfs);
  }

  // ---------------------------------------------------------------- 工具方法

  /** 去掉 ./ 前缀，拒绝绝对路径和 ..，防止解压到 rootfs 外面。 */
  private static String normalize(String name) {
    while (name.startsWith("./")) name = name.substring(2);
    while (name.startsWith("/")) name = name.substring(1);
    if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
    if (name.isEmpty() || name.equals(".")) return null;
    for (String part : name.split("/")) if (part.equals("..")) return null;
    return name;
  }

  static void deleteRecursively(File file) throws IOException {
    if (!file.exists() && !isSymlink(file)) return;
    if (file.isDirectory() && !isSymlink(file)) {
      file.setWritable(true);
      file.setExecutable(true);
      File[] children = file.listFiles();
      if (children != null) for (File child : children) deleteRecursively(child);
    }
    if (!file.delete() && (file.exists() || isSymlink(file)))
      throw new IOException("无法删除 " + file);
  }

  private static boolean isSymlink(File file) {
    try {
      return android.system.OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode);
    } catch (ErrnoException missing) {
      return false;
    }
  }

  private static void mkdirs(File directory) throws IOException {
    if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
      throw new IOException("无法创建目录 " + directory);
  }

  private static void symlink(String target, File link) throws IOException {
    try {
      Os.symlink(target, link.getAbsolutePath());
    } catch (ErrnoException error) {
      throw new IOException("创建符号链接失败 " + link + " -> " + target, error);
    }
  }

  private static void chmod(File file, int mode) {
    try {
      Os.chmod(file.getAbsolutePath(), mode);
    } catch (ErrnoException ignored) {
      // 权限位不是致命问题，保持默认即可
    }
  }

  private static void copyFile(File source, File target) throws IOException {
    try (InputStream in = new FileInputStream(source);
        OutputStream out = new FileOutputStream(target)) {
      Io.copy(in, out);
    }
    chmod(target, source.canExecute() ? 0755 : 0644);
  }

  private static String read(File file) {
    try (InputStream in = new FileInputStream(file)) {
      return new String(Io.readAll(in), StandardCharsets.UTF_8);
    } catch (IOException missing) {
      return "";
    }
  }

  private static void write(File file, String text) throws IOException {
    mkdirs(file.getParentFile());
    file.delete();
    try (OutputStream out = new FileOutputStream(file)) {
      out.write(text.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static void append(File file, String text) throws IOException {
    if (text.isEmpty()) return;
    try (OutputStream out = new FileOutputStream(file, true)) {
      out.write(text.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static String readAsset(Context context, String name) throws IOException {
    try (InputStream in = context.getAssets().open(name)) {
      return new String(Io.readAll(in), StandardCharsets.UTF_8).trim();
    }
  }

  private static void writeAsset(Context context, String asset, File target, int mode)
      throws IOException {
    mkdirs(target.getParentFile());
    target.delete();
    try (InputStream in = context.getAssets().open(asset);
        OutputStream out = new FileOutputStream(target)) {
      Io.copy(in, out);
    }
    chmod(target, mode);
  }

  private static final class CountingInputStream extends FilterInputStream {
    long count;

    CountingInputStream(InputStream in) {
      super(in);
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b != -1) count++;
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n = super.read(b, off, len);
      if (n > 0) count += n;
      return n;
    }

    @Override
    public long skip(long n) throws IOException {
      long skipped = super.skip(n);
      count += skipped;
      return skipped;
    }
  }
}
