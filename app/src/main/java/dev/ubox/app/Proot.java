package dev.ubox.app;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 组装进入 rootfs 的 proot 命令行。参数与 DSHA 的 ContainerRuntime.Proot 一致：
 * Termux 版 proot 放在 jniLibs 里随 APK 解压到 nativeLibraryDir（Android 10+ 只允许从那里 exec）。
 */
final class Proot {
  static final String GUEST_PATH =
      "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

  private final File nativeDir;
  private final Rootfs rootfs;
  private Boolean hardlinks;

  Proot(Context context, Rootfs rootfs) {
    this.nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
    this.rootfs = rootfs;
  }

  File executable() {
    return new File(nativeDir, "libproot.so");
  }

  /** proot 依赖 libtalloc.so.2 / libandroid-shmem.so，jniLibs 里只能用 lib*.so 命名，所以复制一份改名。 */
  void prepare() throws IOException {
    copyIfChanged(new File(nativeDir, "libtalloc.so"), new File(rootfs.lib, "libtalloc.so.2"));
    copyIfChanged(new File(nativeDir, "libandroidshmem.so"), new File(rootfs.lib, "libandroid-shmem.so"));
  }

  /** 完整 argv（argv[0] 为 proot 本身），guest 内用 env -i 得到干净的环境再执行命令。 */
  String[] argv(String... guestCommand) {
    List<String> argv = new ArrayList<>();
    argv.add(executable().getAbsolutePath());
    if (!hardlinksSupported()) {
      // Android 拒绝 link() 时由 proot 用符号链接模拟硬链接（dpkg 需要）
      argv.add("--link2symlink");
      // 模拟出的链接指向宿主绝对路径，容器内必须能按同一路径访问，否则 dpkg chown/stat 报 ENOENT
      File l2s = new File(rootfs.rootfs, ".l2s");
      l2s.mkdirs();
      argv.add("-b");
      argv.add(l2s.getAbsolutePath());
    }
    argv.add("-L");
    argv.add("--kill-on-exit");
    argv.add("-0");
    argv.add("--rootfs=" + rootfs.rootfs.getAbsolutePath());
    argv.add("--cwd=/root");
    // Android 自带的工具（如 ping）需要 bionic linker 和系统库
    bind(argv, "/system", "/system");
    bind(argv, "/apex", "/apex");
    bind(argv, "/dev", "/dev");
    bind(argv, "/proc", "/proc");
    bind(argv, "/sys", "/sys");
    bind(argv, "/dev/urandom", "/dev/random");
    bind(argv, "/proc/self/fd", "/dev/fd");
    bind(argv, "/proc/self/fd/0", "/dev/stdin");
    bind(argv, "/proc/self/fd/1", "/dev/stdout");
    bind(argv, "/proc/self/fd/2", "/dev/stderr");
    bind(argv, new File(rootfs.rootfs, "tmp").getAbsolutePath(), "/dev/shm");
    for (String name : new String[] {"loadavg", "stat", "uptime", "version", "vmstat"}) {
      if (!new File("/proc/" + name).canRead())
        bind(argv, rootfs.fakeProc(name).getAbsolutePath(), "/proc/" + name);
    }
    File storage = new File("/storage/emulated/0");
    if (storage.canRead()) {
      bind(argv, storage.getAbsolutePath(), "/sdcard");
      bind(argv, storage.getAbsolutePath(), storage.getAbsolutePath());
    }
    argv.add("/usr/bin/env");
    argv.add("-i");
    argv.add("HOME=/root");
    argv.add("USER=root");
    argv.add("LOGNAME=root");
    argv.add("SHELL=/bin/bash");
    argv.add("TERM=xterm-256color");
    argv.add("COLORTERM=truecolor");
    argv.add("LANG=C.UTF-8");
    argv.add("TMPDIR=/tmp");
    argv.add("PATH=" + GUEST_PATH);
    if (guestCommand.length == 0) {
      argv.add("/bin/bash");
      argv.add("-l");
    } else {
      for (String part : guestCommand) argv.add(part);
    }
    return argv.toArray(new String[0]);
  }

  /** proot 自身的环境变量（不会传进 guest，guest 由 env -i 重建）。 */
  String[] environment() {
    String loader = new File(nativeDir, "libprootloader.so").getAbsolutePath();
    List<String> env = new ArrayList<>();
    env.add("PROOT_TMP_DIR=" + rootfs.tmp.getAbsolutePath());
    env.add("PROOT_LOADER=" + loader);
    File loader32 = new File(nativeDir, "libprootloader32.so");
    if (loader32.isFile()) env.add("PROOT_LOADER_32=" + loader32.getAbsolutePath());
    env.add("LD_LIBRARY_PATH=" + rootfs.lib.getAbsolutePath() + ":" + nativeDir.getAbsolutePath());
    if (!hardlinksSupported()) {
      File l2s = new File(rootfs.rootfs, ".l2s");
      l2s.mkdirs();
      env.add("PROOT_L2S_DIR=" + l2s.getAbsolutePath());
    }
    env.add("HOME=/root");
    env.add("TERM=xterm-256color");
    return env.toArray(new String[0]);
  }

  /** 非交互地在 guest 里执行一段 bash 脚本，stdout/stderr 合并读取。 */
  String run(String script, long timeoutMs) throws IOException, InterruptedException {
    prepare();
    ProcessBuilder builder =
        new ProcessBuilder(argv("/bin/bash", "-c", script)).redirectErrorStream(true);
    builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
    builder.environment().clear();
    for (String entry : environment()) {
      int split = entry.indexOf('=');
      builder.environment().put(entry.substring(0, split), entry.substring(split + 1));
    }
    Process process = builder.start();
    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
    Thread reader =
        new Thread(
            () -> {
              try (InputStream in = process.getInputStream()) {
                Io.copy(in, output);
              } catch (IOException ignored) {
                // 进程被结束时流会关闭
              }
            },
            "proot-run-output");
    reader.start();
    if (!process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
      process.destroyForcibly();
      throw new IOException("命令超时");
    }
    reader.join(2000);
    return new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
  }

  /** 实测当前存储能否创建硬链接；部分 Android 版本的 SELinux 策略会拒绝。 */
  boolean hardlinksSupported() {
    if (hardlinks != null) return hardlinks;
    File a = new File(rootfs.tmp, "link-probe-a"), b = new File(rootfs.tmp, "link-probe-b");
    boolean supported;
    try {
      rootfs.tmp.mkdirs();
      a.delete();
      b.delete();
      new FileOutputStream(a).close();
      Os.link(a.getAbsolutePath(), b.getAbsolutePath());
      supported = true;
    } catch (IOException | ErrnoException denied) {
      supported = false;
    } finally {
      a.delete();
      b.delete();
    }
    hardlinks = supported;
    return supported;
  }

  private static void bind(List<String> argv, String host, String guest) {
    if (!new File(host).exists()) return;
    argv.add("-b");
    argv.add(host.equals(guest) ? host : host + ":" + guest);
  }

  private static void copyIfChanged(File source, File target) throws IOException {
    if (target.isFile() && target.length() == source.length()
        && target.lastModified() >= source.lastModified()) return;
    target.getParentFile().mkdirs();
    File partial = new File(target.getPath() + ".part");
    try (InputStream in = new FileInputStream(source);
        OutputStream out = new FileOutputStream(partial)) {
      Io.copy(in, out);
    }
    if (!partial.renameTo(target)) throw new IOException("无法写入 " + target);
  }
}
