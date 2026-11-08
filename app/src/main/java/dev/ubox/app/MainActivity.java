package dev.ubox.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;
import java.io.IOException;
import java.util.List;

public class MainActivity extends Activity {
  private static final String TAG = "UbuntuBox";
  private static final int REQUEST_SETTINGS = 2;

  private TerminalView terminalView;
  private View installPanel, extraKeysScroll;
  private TextView installStatus;
  private ProgressBar installProgress;
  private Button installRetry;
  private TerminalService service;
  private boolean bound;
  private int textSize;
  private boolean ctrlDown, altDown;
  private Button ctrlButton, altButton;

  private final ServiceConnection connection =
      new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
          service = ((TerminalService.LocalBinder) binder).service();
          service.setOnExit(MainActivity.this::finishAndRemoveTask);
          for (TerminalSession session : service.sessions())
            session.updateTerminalSessionClient(sessionClient);
          List<TerminalSession> sessions = service.sessions();
          if (sessions.isEmpty()) newSession();
          else attach(sessions.get(sessions.size() - 1));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
          service = null;
        }
      };

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    setActionBar(findViewById(R.id.toolbar));
    EdgeToEdge.padForSystemBars(findViewById(R.id.root));
    terminalView = findViewById(R.id.terminal);
    installPanel = findViewById(R.id.install_panel);
    installStatus = findViewById(R.id.install_status);
    installProgress = findViewById(R.id.install_progress);
    installRetry = findViewById(R.id.install_retry);
    extraKeysScroll = findViewById(R.id.extra_keys_scroll);

    textSize = (int) TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics());
    terminalView.setTerminalViewClient(viewClient);
    terminalView.setTextSize(textSize);
    terminalView.setTypeface(Typeface.MONOSPACE);
    buildExtraKeys();
    installRetry.setOnClickListener(v -> ensureInstalledThenStart());

    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);

    ensureInstalledThenStart();
  }

  @Override
  protected void onDestroy() {
    if (service != null) {
      service.setOnExit(null);
      service.detachClient(new SilentSessionClient());
    }
    if (bound) unbindService(connection);
    bound = false;
    super.onDestroy();
  }

  // ---------------------------------------------------------------- 安装与启动

  private void ensureInstalledThenStart() {
    Rootfs rootfs = new Rootfs(this);
    if (rootfs.isInstalled()) {
      startTerminal();
      return;
    }
    showInstall("准备解压 Ubuntu…");
    new Thread(
            () -> {
              try {
                rootfs.install(
                    this,
                    (stage, done, total) ->
                        runOnUiThread(
                            () -> {
                              installStatus.setText(stage);
                              installProgress.setProgress(
                                  total > 0 ? (int) (done * 1000 / total) : 0);
                            }));
                runOnUiThread(this::startTerminal);
              } catch (IOException | RuntimeException error) {
                Log.e(TAG, "install failed", error);
                runOnUiThread(
                    () -> {
                      installStatus.setText("安装失败：" + error.getMessage());
                      installRetry.setVisibility(View.VISIBLE);
                    });
              }
            },
            "rootfs-install")
        .start();
  }

  private void showInstall(String text) {
    installPanel.setVisibility(View.VISIBLE);
    installRetry.setVisibility(View.GONE);
    terminalView.setVisibility(View.GONE);
    extraKeysScroll.setVisibility(View.GONE);
    installStatus.setText(text);
    installProgress.setProgress(0);
  }

  private void startTerminal() {
    installPanel.setVisibility(View.GONE);
    terminalView.setVisibility(View.VISIBLE);
    extraKeysScroll.setVisibility(View.VISIBLE);
    Intent intent = new Intent(this, TerminalService.class);
    startForegroundService(intent);
    if (!bound) bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
  }

  private void newSession() {
    if (service == null) return;
    try {
      attach(service.createSession(sessionClient));
    } catch (IOException | RuntimeException error) {
      Log.e(TAG, "session failed", error);
      Toast.makeText(this, "启动失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  private void attach(TerminalSession session) {
    terminalView.attachSession(session);
    terminalView.requestFocus();
    updateTitle();
  }

  private void updateTitle() {
    TerminalSession session = terminalView.getCurrentSession();
    if (session == null) return;
    String title = session.getTitle();
    String name = session.mSessionName + (title == null || title.isEmpty() ? "" : " · " + title);
    setTitle(session.isRunning() ? name : name + "（已退出，回车关闭）");
  }

  /** 已退出的会话保留输出，按回车后才关闭，便于看到错误信息。 */
  private boolean closeIfFinished(TerminalSession session) {
    if (session == null || session.isRunning() || service == null) return false;
    service.removeSession(session);
    List<TerminalSession> sessions = service.sessions();
    if (sessions.isEmpty()) newSession();
    else attach(sessions.get(sessions.size() - 1));
    return true;
  }

  // ---------------------------------------------------------------- 菜单

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.main, menu);
    return true;
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    menu.findItem(R.id.menu_wakelock).setChecked(service != null && service.isWakeLockHeld());
    return super.onPrepareOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    int id = item.getItemId();
    if (id == R.id.menu_new_session) newSession();
    else if (id == R.id.menu_sessions) chooseSession();
    else if (id == R.id.menu_settings)
      startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS);
    else if (id == R.id.menu_keyboard) showKeyboard();
    else if (id == R.id.menu_wakelock && service != null) service.setWakeLock(!item.isChecked());
    else if (id == R.id.menu_storage) requestStorage();
    else if (id == R.id.menu_reset) confirmReset();
    else if (id == R.id.menu_exit && service != null) service.exit();
    else return super.onOptionsItemSelected(item);
    return true;
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != REQUEST_SETTINGS || resultCode != RESULT_OK || data == null) return;
    String name = data.getStringExtra(SettingsActivity.EXTRA_NAME);
    String command = data.getStringExtra(SettingsActivity.EXTRA_COMMAND);
    if (service == null || command == null) return;
    try {
      attach(service.createSession(sessionClient, name, command));
    } catch (IOException | RuntimeException error) {
      Log.e(TAG, "install session failed", error);
      Toast.makeText(this, "启动失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  private void chooseSession() {
    if (service == null) return;
    List<TerminalSession> sessions = service.sessions();
    String[] names = new String[sessions.size()];
    for (int i = 0; i < names.length; i++) {
      TerminalSession s = sessions.get(i);
      names[i] = s.mSessionName + (s.isRunning() ? "" : "（已退出）")
          + (s.getTitle() == null ? "" : " · " + s.getTitle());
    }
    new AlertDialog.Builder(this)
        .setTitle("切换会话")
        .setItems(names, (dialog, which) -> attach(sessions.get(which)))
        .show();
  }

  private void requestStorage() {
    if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
      Toast.makeText(this, "已授权，新会话中可访问 /sdcard", Toast.LENGTH_SHORT).show();
      return;
    }
    if (Build.VERSION.SDK_INT < 30) {
      Toast.makeText(this, "Android 10 及以下不需要该权限", Toast.LENGTH_SHORT).show();
      return;
    }
    startActivity(
        new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:" + getPackageName())));
  }

  private void confirmReset() {
    new AlertDialog.Builder(this)
        .setTitle("重置 Ubuntu 环境")
        .setMessage("将结束所有会话并删除整个 Ubuntu 环境（包括 /root 下的所有文件和已安装的软件），然后重新解压。此操作不可恢复。")
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "删除并重置",
            (dialog, which) -> {
              if (service != null) {
                service.setOnExit(null);
                for (TerminalSession s : List.copyOf(service.sessions())) service.removeSession(s);
              }
              showInstall("正在删除旧环境…");
              new Thread(
                      () -> {
                        try {
                          new Rootfs(this).delete();
                          runOnUiThread(
                              () -> {
                                if (service != null)
                                  service.setOnExit(MainActivity.this::finishAndRemoveTask);
                                ensureInstalledThenStart();
                              });
                        } catch (IOException error) {
                          runOnUiThread(() -> installStatus.setText("删除失败：" + error.getMessage()));
                        }
                      },
                      "rootfs-reset")
                  .start();
            })
        .show();
  }

  private void showKeyboard() {
    terminalView.requestFocus();
    getSystemService(InputMethodManager.class).showSoftInput(terminalView, 0);
  }

  // ---------------------------------------------------------------- 额外按键

  private void buildExtraKeys() {
    LinearLayout row = findViewById(R.id.extra_keys);
    addKey(row, "ESC", v -> sendKey(KeyEvent.KEYCODE_ESCAPE));
    addKey(row, "TAB", v -> sendKey(KeyEvent.KEYCODE_TAB));
    ctrlButton = addKey(row, "CTRL", v -> {
      ctrlDown = !ctrlDown;
      refreshModifiers();
    });
    altButton = addKey(row, "ALT", v -> {
      altDown = !altDown;
      refreshModifiers();
    });
    addKey(row, "←", v -> sendKey(KeyEvent.KEYCODE_DPAD_LEFT));
    addKey(row, "↓", v -> sendKey(KeyEvent.KEYCODE_DPAD_DOWN));
    addKey(row, "↑", v -> sendKey(KeyEvent.KEYCODE_DPAD_UP));
    addKey(row, "→", v -> sendKey(KeyEvent.KEYCODE_DPAD_RIGHT));
    for (String symbol : new String[] {"-", "/", "|", "~", "*", "$", "&"})
      addKey(row, symbol, v -> terminalView.inputCodePoint(symbol.codePointAt(0), false, false));
    addKey(row, "HOME", v -> sendKey(KeyEvent.KEYCODE_MOVE_HOME));
    addKey(row, "END", v -> sendKey(KeyEvent.KEYCODE_MOVE_END));
    addKey(row, "PGUP", v -> sendKey(KeyEvent.KEYCODE_PAGE_UP));
    addKey(row, "PGDN", v -> sendKey(KeyEvent.KEYCODE_PAGE_DOWN));
    addKey(row, "粘贴", v -> sessionClient.onPasteTextFromClipboard(terminalView.getCurrentSession()));
  }

  private Button addKey(LinearLayout row, String label, View.OnClickListener listener) {
    Button button = new Button(this, null, 0, android.R.style.Widget_DeviceDefault_Button_Borderless);
    button.setText(label);
    button.setAllCaps(false);
    button.setTextColor(Color.WHITE);
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    button.setGravity(Gravity.CENTER);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    int pad = (int) TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 12, getResources().getDisplayMetrics());
    button.setPadding(pad, 0, pad, 0);
    button.setOnClickListener(listener);
    row.addView(button, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
    return button;
  }

  private void sendKey(int keyCode) {
    terminalView.onKeyDown(keyCode, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
  }

  private void refreshModifiers() {
    ctrlButton.setTextColor(ctrlDown ? 0xFFE95420 : Color.WHITE);
    altButton.setTextColor(altDown ? 0xFFE95420 : Color.WHITE);
  }

  // ---------------------------------------------------------------- Termux 回调

  private final TerminalViewClient viewClient =
      new TerminalViewClient() {
        @Override
        public float onScale(float scale) {
          if (scale < 0.9f || scale > 1.1f) {
            int step = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 1, getResources().getDisplayMetrics());
            int min = step * 6, max = step * 32;
            textSize = Math.max(min, Math.min(max, textSize + (scale > 1f ? step : -step)));
            terminalView.setTextSize(textSize);
            return 1.0f;
          }
          return scale;
        }

        @Override
        public void onSingleTapUp(MotionEvent e) {
          showKeyboard();
        }

        @Override
        public boolean shouldBackButtonBeMappedToEscape() {
          return false;
        }

        @Override
        public boolean shouldEnforceCharBasedInput() {
          return true;
        }

        @Override
        public boolean shouldUseCtrlSpaceWorkaround() {
          return false;
        }

        @Override
        public boolean isTerminalViewSelected() {
          return true;
        }

        @Override
        public void copyModeChanged(boolean copyMode) {}

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
          if (keyCode == KeyEvent.KEYCODE_ENTER && closeIfFinished(session)) return true;
          return false;
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent e) {
          return false;
        }

        @Override
        public boolean onLongPress(MotionEvent event) {
          return false;
        }

        // 与 Termux 一致：读取即消费，粘滞键只作用于下一次按键
        @Override
        public boolean readControlKey() {
          boolean value = ctrlDown;
          if (value) {
            ctrlDown = false;
            refreshModifiers();
          }
          return value;
        }

        @Override
        public boolean readAltKey() {
          boolean value = altDown;
          if (value) {
            altDown = false;
            refreshModifiers();
          }
          return value;
        }

        @Override
        public boolean readShiftKey() {
          return false;
        }

        @Override
        public boolean readFnKey() {
          return false;
        }

        @Override
        public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
          return (codePoint == '\r' || codePoint == '\n') && closeIfFinished(session);
        }

        @Override
        public void onEmulatorSet() {}

        @Override
        public void logError(String tag, String message) {
          Log.e(tag, message);
        }

        @Override
        public void logWarn(String tag, String message) {
          Log.w(tag, message);
        }

        @Override
        public void logInfo(String tag, String message) {
          Log.i(tag, message);
        }

        @Override
        public void logDebug(String tag, String message) {}

        @Override
        public void logVerbose(String tag, String message) {}

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) {
          Log.e(tag, message, e);
        }

        @Override
        public void logStackTrace(String tag, Exception e) {
          Log.e(tag, "error", e);
        }
      };

  private final TerminalSessionClient sessionClient =
      new SilentSessionClient() {
        @Override
        public void onTextChanged(TerminalSession session) {
          if (session == terminalView.getCurrentSession()) terminalView.onScreenUpdated();
        }

        @Override
        public void onTitleChanged(TerminalSession session) {
          if (session == terminalView.getCurrentSession()) updateTitle();
        }

        @Override
        public void onSessionFinished(TerminalSession session) {
          if (session == terminalView.getCurrentSession()) updateTitle();
        }

        @Override
        public void onCopyTextToClipboard(TerminalSession session, String text) {
          getSystemService(ClipboardManager.class)
              .setPrimaryClip(ClipData.newPlainText("terminal", text));
        }

        @Override
        public void onPasteTextFromClipboard(TerminalSession session) {
          ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
          if (clip == null || clip.getItemCount() == 0 || session == null) return;
          CharSequence text = clip.getItemAt(0).coerceToText(MainActivity.this);
          if (text != null && session.getEmulator() != null)
            session.getEmulator().paste(text.toString());
        }

        @Override
        public void onColorsChanged(TerminalSession session) {
          if (session == terminalView.getCurrentSession()) terminalView.invalidate();
        }
      };

  /** Activity 不在时使用的空回调。 */
  static class SilentSessionClient implements TerminalSessionClient {
    @Override public void onTextChanged(TerminalSession session) {}
    @Override public void onTitleChanged(TerminalSession session) {}
    @Override public void onSessionFinished(TerminalSession session) {}
    @Override public void onCopyTextToClipboard(TerminalSession session, String text) {}
    @Override public void onPasteTextFromClipboard(TerminalSession session) {}
    @Override public void onBell(TerminalSession session) {}
    @Override public void onColorsChanged(TerminalSession session) {}
    @Override public void onTerminalCursorStateChange(boolean state) {}
    @Override public Integer getTerminalCursorStyle() { return null; }
    @Override public void logError(String tag, String message) { Log.e(tag, message); }
    @Override public void logWarn(String tag, String message) { Log.w(tag, message); }
    @Override public void logInfo(String tag, String message) {}
    @Override public void logDebug(String tag, String message) {}
    @Override public void logVerbose(String tag, String message) {}
    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { Log.e(tag, message, e); }
    @Override public void logStackTrace(String tag, Exception e) { Log.e(tag, "error", e); }
  }
}
