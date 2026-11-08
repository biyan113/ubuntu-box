package dev.ubox.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** InputStream.transferTo/readAllBytes 是 API 33 才有的，低版本用这里的等价实现。 */
final class Io {
  private Io() {}

  static void copy(InputStream in, OutputStream out) throws IOException {
    byte[] buffer = new byte[8192];
    int read;
    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
  }

  static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    copy(in, out);
    return out.toByteArray();
  }
}
