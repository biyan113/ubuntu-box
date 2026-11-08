package dev.ubox.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 软件源切换和常用工具的一键安装。安装在新终端会话里执行，进度和错误都直接可见。 */
public class SettingsActivity extends Activity {
  static final String EXTRA_NAME = "name";
  static final String EXTRA_COMMAND = "command";
  private static final String TAG = "UbuntuBox";

  enum Kind { APT, NODE, NPM }

  /** packages：apt/npm 包名；check：NODE/NPM 用来判断是否已安装的命令名。 */
  record Tool(String id, String title, String summary, Kind kind, String[] packages, String check) {
    String installCommand() {
      switch (kind) {
        case APT: return "ubox-install apt " + String.join(" ", packages);
        case NPM: return "ubox-install npm " + String.join(" ", packages);
        default: return "ubox-install node";
      }
    }

    String removeCommand() {
      switch (kind) {
        case APT: return "ubox-install apt-remove " + String.join(" ", packages);
        case NPM: return "ubox-install npm-remove " + String.join(" ", packages);
        default: return "rm -rf /usr/local/bin/node /usr/local/bin/npm /usr/local/bin/npx"
            + " /usr/local/bin/corepack /usr/local/lib/node_modules/npm /usr/local/include/node"
            + " && echo 已卸载 Node.js";
      }
    }

    String statusCheck() {
      if (kind == Kind.APT)
        return "n=$(dpkg-query -W -f='${db:Status-Status}\\n' " + String.join(" ", packages)
            + " 2>/dev/null | grep -c '^installed$'); [ \"$n\" = " + packages.length
            + " ] && echo " + id + "=1 || echo " + id + "=0";
      return "command -v " + check + " >/dev/null 2>&1 && echo " + id + "=1 || echo " + id + "=0";
    }
  }

  record Section(String title, Tool... tools) {}

  private static Tool apt(String id, String title, String summary, String... packages) {
    return new Tool(id, title, summary, Kind.APT, packages, null);
  }

  private static Tool npm(String id, String title, String summary, String bin, String pkg) {
    return new Tool(id, title, summary, Kind.NPM, new String[] {pkg}, bin);
  }

  static final Section[] SECTIONS = {
    new Section(
        "常用工具",
        apt("net", "网络工具", "curl、wget、dig、ifconfig 和 HTTPS 根证书（ping 已内置）",
            "curl", "wget", "ca-certificates", "dnsutils", "net-tools"),
        apt("cli", "常用命令", "less、htop、tree、zip/unzip、xz、jq、ripgrep、file、tmux",
            "less", "htop", "tree", "zip", "unzip", "xz-utils", "jq", "ripgrep", "file",
            "procps", "tmux"),
        apt("editor", "编辑器", "nano、vim", "nano", "vim"),
        apt("ssh", "SSH 客户端", "ssh、scp、ssh-keygen", "openssh-client")),
    new Section(
        "开发环境",
        apt("git", "Git", "版本管理，Agent 改代码基本都需要", "git", "ca-certificates"),
        new Tool("node", "Node.js 22", "官方 linux-arm64 版本，含 npm；AI Agent 依赖它",
            Kind.NODE, new String[0], "node"),
        apt("python", "Python 3", "python3、pip、venv", "python3", "python3-pip", "python3-venv"),
        apt("build", "编译工具链", "gcc、g++、make；npm 原生模块编译需要", "build-essential")),
    new Section(
        "AI Agent（需要 Node.js，未安装时会自动先装）",
        npm("claude", "Claude Code", "Anthropic · npm @anthropic-ai/claude-code", "claude",
            "@anthropic-ai/claude-code"),
        npm("codex", "Codex CLI", "OpenAI · npm @openai/codex", "codex", "@openai/codex"),
        npm("gemini", "Gemini CLI", "Google · npm @google/gemini-cli", "gemini",
            "@google/gemini-cli"),
        npm("opencode", "OpenCode", "npm opencode-ai", "opencode", "opencode-ai"),
        npm("ccconnect", "CC-Connect", "把 Claude Code 接入飞书/Telegram/微信等 · npm cc-connect",
            "cc-connect", "cc-connect")),
  };

  /** 「一键安装推荐」包含的条目。 */
  private static final String[] RECOMMENDED = {"net", "cli", "editor", "git"};

  private static final String[][] MIRRORS = {
    {"official", "Ubuntu 官方", "ports.ubuntu.com"},
    {"tuna", "清华 TUNA", "mirrors.tuna.tsinghua.edu.cn"},
    {"ustc", "中科大 USTC", "mirrors.ustc.edu.cn"},
    {"aliyun", "阿里云", "mirrors.aliyun.com"},
  };

  private final Map<String, Boolean> installed = new HashMap<>();
  private final Map<String, Button> buttons = new HashMap<>();
  private TextView mirrorValue, statusLine;
  private TextView sshState, sshCommand, sshKeys, sshPassword;
  private Button sshButton;
  private boolean sshInstalled;
  private int sshKeyCount;
  private boolean sshHasPassword;
  private TextView ccState;
  private Button ccButton;
  private boolean ccConfigured, ccRunning;
  private TerminalService service;
  private final android.content.ServiceConnection connection =
      new android.content.ServiceConnection() {
        @Override
        public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
          service = ((TerminalService.LocalBinder) binder).service();
          updateSshUi();
        }

        @Override
        public void onServiceDisconnected(android.content.ComponentName name) {
          service = null;
        }
      };
  private Proot proot;
  private float density;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    density = getResources().getDisplayMetrics().density;
    proot = new Proot(this, new Rootfs(this));

    LinearLayout list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);
    list.setPadding(dp(16), dp(8), dp(16), dp(24));

    statusLine = text("正在检查安装状态…", 13, 0xFF9E9E9E);
    statusLine.setPadding(0, dp(8), 0, dp(4));
    list.addView(statusLine);

    list.addView(header("软件源"));
    LinearLayout mirrorRow = row("apt 软件源", null);
    mirrorValue = (TextView) ((LinearLayout) mirrorRow.getChildAt(0)).getChildAt(1);
    mirrorValue.setText("读取中…");
    mirrorRow.setOnClickListener(v -> chooseMirror());
    list.addView(mirrorRow);

    buildSshSection(list);

    Button recommended = new Button(this);
    recommended.setText("一键安装推荐（网络工具 + 常用命令 + 编辑器 + Git）");
    recommended.setAllCaps(false);
    recommended.setOnClickListener(v -> installRecommended());
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.topMargin = dp(12);
    list.addView(recommended, lp);

    for (Section section : SECTIONS) {
      list.addView(header(section.title()));
      for (Tool tool : section.tools()) {
        Button action = actionButton();
        action.setOnClickListener(v -> onToolClicked(tool));
        buttons.put(tool.id(), action);
        LinearLayout row = row(tool.title(), action);
        ((TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1)).setText(tool.summary());
        row.setOnClickListener(v -> onToolClicked(tool));
        list.addView(row);
      }
    }

    buildCcSection(list);

    Toolbar toolbar = new Toolbar(this);
    toolbar.setBackgroundColor(0xFF1E1E1E);
    toolbar.setTitleTextColor(Color.WHITE);
    toolbar.setMinimumHeight(dp(48));

    ScrollView scroll = new ScrollView(this);
    scroll.addView(list);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(0xFF121212);
    root.addView(toolbar, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    root.addView(scroll, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    setContentView(root);
    setActionBar(toolbar);
    setTitle("设置");
    getActionBar().setDisplayHomeAsUpEnabled(true);
    EdgeToEdge.padForSystemBars(root);
    bindService(new Intent(this, TerminalService.class), connection, BIND_AUTO_CREATE);
  }

  @Override
  protected void onDestroy() {
    unbindService(connection);
    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();
    refreshStatus();
  }

  @Override
  public boolean onNavigateUp() {
    finish();
    return true;
  }

  // ---------------------------------------------------------------- 状态

  private void refreshStatus() {
    StringBuilder script = new StringBuilder();
    script.append("grep -m1 '^URIs:' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null"
        + " | sed 's/^URIs: */mirror=/'\n");
    for (Section section : SECTIONS)
      for (Tool tool : section.tools()) script.append(tool.statusCheck()).append('\n');
    script.append("[ -x /usr/sbin/sshd ] && echo sshd=1 || echo sshd=0\n");    script.append("echo sshkeys=$(grep -cE '^(ssh-|ecdsa-|sk-)' /root/.ssh/authorized_keys 2>/dev/null || echo 0)\n");
    script.append("awk -F: '$1==\"root\"{print \"sshpw=\" (($2 ~ /^[!*]/ || $2 == \"\") ? 0 : 1)}' /etc/shadow\n");
    script.append("[ -f /root/.cc-connect/config.toml ] && echo ccconf=1 || echo ccconf=0\n");
    script.append("pgrep -f 'cc-connect --config' >/dev/null 2>&1 && echo ccrun=1 || echo ccrun=0\n");
    new Thread(
            () -> {
              String output;
              try {
                output = proot.run(script.toString(), 30_000);
              } catch (Exception error) {
                Log.e(TAG, "status check failed", error);
                runOnUiThread(() -> statusLine.setText("检查安装状态失败：" + error.getMessage()));
                return;
              }
              Map<String, Boolean> result = new HashMap<>();
              String mirror = "";
              int keys = 0;
              for (String line : output.split("\n")) {
                int split = line.indexOf('=');
                if (split <= 0) continue;
                String key = line.substring(0, split), value = line.substring(split + 1).trim();
                if (key.equals("mirror")) mirror = value;
                else if (key.equals("sshkeys")) keys = parseInt(value);
                else result.put(key, value.equals("1"));
              }
              int keyCount = keys;
              String mirrorText = describeMirror(mirror);
              runOnUiThread(
                  () -> {
                    installed.clear();
                    installed.putAll(result);
                    mirrorValue.setText(mirrorText);
                    sshInstalled = Boolean.TRUE.equals(result.get("sshd"));
                    sshHasPassword = Boolean.TRUE.equals(result.get("sshpw"));
                    sshKeyCount = keyCount;
                    ccConfigured = Boolean.TRUE.equals(result.get("ccconf"));
                    ccRunning = Boolean.TRUE.equals(result.get("ccrun"));
                    updateSshUi();
                    updateCcUi();
                    for (Map.Entry<String, Button> entry : buttons.entrySet())
                      styleButton(entry.getValue(), Boolean.TRUE.equals(installed.get(entry.getKey())));
                    statusLine.setText("安装会在新的终端会话中进行，完成后返回这里即可看到状态");
                  });
            },
            "settings-status")
        .start();
  }

  private static int parseInt(String value) {
    try {
      return Integer.parseInt(value.split("\\s+")[0]);
    } catch (NumberFormatException error) {
      return 0;
    }
  }

  private static String describeMirror(String uri) {
    for (String[] mirror : MIRRORS) if (uri.contains(mirror[2])) return mirror[1] + " · " + mirror[2];
    return uri.isEmpty() ? "未知" : uri;
  }

  // ---------------------------------------------------------------- 操作

  private void chooseMirror() {
    String[] labels = new String[MIRRORS.length];
    for (int i = 0; i < MIRRORS.length; i++) labels[i] = MIRRORS[i][1] + "\n" + MIRRORS[i][2];
    new AlertDialog.Builder(this)
        .setTitle("选择 apt 软件源")
        .setItems(labels, (dialog, which) -> applyMirror(MIRRORS[which][0], MIRRORS[which][1]))
        .show();
  }

  private void applyMirror(String id, String label) {
    mirrorValue.setText("切换中…");
    new Thread(
            () -> {
              String message;
              try {
                proot.run("ubox-mirror " + id, 15_000);
                message = "已切换到" + label + "，下次安装时会自动 apt update";
              } catch (Exception error) {
                message = "切换失败：" + error.getMessage();
              }
              String toast = message;
              runOnUiThread(
                  () -> {
                    Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
                    refreshStatus();
                  });
            },
            "settings-mirror")
        .start();
  }

  private void onToolClicked(Tool tool) {
    if (!Boolean.TRUE.equals(installed.get(tool.id()))) {
      runInTerminal("安装 " + tool.title(), tool.installCommand());
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle(tool.title() + " 已安装")
        .setItems(
            new String[] {"重新安装 / 更新", "卸载"},
            (dialog, which) -> {
              if (which == 0) runInTerminal("安装 " + tool.title(), tool.installCommand());
              else confirmRemove(tool);
            })
        .show();
  }

  private void confirmRemove(Tool tool) {
    new AlertDialog.Builder(this)
        .setTitle("卸载 " + tool.title() + "？")
        .setMessage(tool.kind() == Kind.APT
            ? "将执行 apt-get remove " + String.join(" ", tool.packages()) + "，依赖它们的其他软件也可能被一起移除。"
            : "将从 /usr/local 中移除。")
        .setNegativeButton("取消", null)
        .setPositiveButton("卸载", (d, w) -> runInTerminal("卸载 " + tool.title(), tool.removeCommand()))
        .show();
  }

  private void installRecommended() {
    Set<String> packages = new LinkedHashSet<>();
    for (Section section : SECTIONS)
      for (Tool tool : section.tools())
        for (String id : RECOMMENDED)
          if (tool.id().equals(id)) for (String p : tool.packages()) packages.add(p);
    runInTerminal("安装推荐工具", "ubox-install apt " + TextUtils.join(" ", packages));
  }

  /** 交给 MainActivity 在新会话中执行，便于看到实时输出。 */
  private void runInTerminal(String name, String command) {
    setResult(RESULT_OK, new Intent().putExtra(EXTRA_NAME, name).putExtra(EXTRA_COMMAND, command));
    finish();
  }

  // ---------------------------------------------------------------- CC-Connect

  private void buildCcSection(LinearLayout list) {
    list.addView(header("CC-Connect 后台服务"));

    ccButton = actionButton();
    ccButton.setOnClickListener(v -> toggleCc());
    LinearLayout stateRow = row("允许后台执行（崩溃自动重启）", ccButton);
    ccState = subtitle(stateRow);
    stateRow.setOnClickListener(v -> toggleCc());
    list.addView(stateRow);

    LinearLayout logRow = row("查看运行日志", null);
    subtitle(logRow).setText("cc-connect 的启动与连接记录");
    logRow.setOnClickListener(v -> showCcLog());
    list.addView(logRow);

    TextView note = text(
        "先在上方「AI Agent」里安装 CC-Connect，并在终端运行一次 cc-connect 完成配置"
            + "（/root/.cc-connect/config.toml）。开启后台执行后，App 启动时会自动拉起，"
            + "进程退出 10 秒内自动重启；锁屏/重启后打开一次 App 即可恢复。",
        12, 0xFF9E9E9E);
    note.setPadding(0, dp(4), 0, 0);
    list.addView(note);
    updateCcUi();
  }

  private void updateCcUi() {
    if (ccButton == null) return;
    boolean installedTool = Boolean.TRUE.equals(installed.get("ccconnect"));
    if (!installedTool) {
      ccState.setText("未安装 CC-Connect");
      ccButton.setText("安装");
      ccButton.setTextColor(0xFFE95420);
    } else if (!ccConfigured) {
      ccState.setText("未配置（/root/.cc-connect/config.toml）");
      ccButton.setText("去配置");
      ccButton.setTextColor(0xFFE95420);
    } else if (ccRunning) {
      ccState.setText("运行中 · 后台执行已允许");
      ccButton.setText("停止");
      ccButton.setTextColor(0xFFE57373);
    } else {
      ccState.setText("已停止");
      ccButton.setText("启动");
      ccButton.setTextColor(0xFFE95420);
    }
  }

  private void toggleCc() {
    if (!Boolean.TRUE.equals(installed.get("ccconnect"))) {
      runInTerminal("安装 CC-Connect", "ubox-install npm cc-connect");
      return;
    }
    if (!ccConfigured) {
      runInTerminal("配置 CC-Connect", "cc-connect");
      return;
    }
    if (service == null) return;
    if (ccRunning) {
      service.stopCcConnect();
      ccRunning = false;
      updateCcUi();
      ccState.postDelayed(() -> refreshStatus(), 1500);
      return;
    }
    ccState.setText("启动中…");
    service.startCcConnect();
    ccState.postDelayed(() -> refreshStatus(), 3000);
  }

  private void showCcLog() {
    new Thread(
            () -> {
              String log = "";
              try {
                log = proot.run("tail -n 80 /root/.cc-connect/cc-connect.log 2>/dev/null", 15_000);
              } catch (Exception error) {
                log = "读取失败：" + error.getMessage();
              }
              final String content = log;
              runOnUiThread(
                  () -> {
                    TextView view = text(content.isEmpty() ? "（暂无日志）" : content, 11, Color.WHITE);
                    view.setTypeface(Typeface.MONOSPACE);
                    view.setTextIsSelectable(true);
                    view.setPadding(dp(16), dp(8), dp(16), dp(8));
                    ScrollView scroll = new ScrollView(this);
                    scroll.addView(view);
                    new AlertDialog.Builder(this)
                        .setTitle("cc-connect 日志")
                        .setView(scroll)
                        .setPositiveButton("关闭", null)
                        .show();
                  });
            },
            "cc-log")
        .start();
  }

  // ---------------------------------------------------------------- SSH

  private void buildSshSection(LinearLayout list) {
    list.addView(header("SSH 远程访问"));

    sshButton = actionButton();
    sshButton.setOnClickListener(v -> toggleSsh());
    LinearLayout stateRow = row("SSH 服务（端口 " + TerminalService.SSH_PORT + "）", sshButton);
    sshState = subtitle(stateRow);
    stateRow.setOnClickListener(v -> toggleSsh());
    list.addView(stateRow);

    Button copy = actionButton();
    copy.setText("复制");
    copy.setTextColor(0xFFE95420);
    copy.setOnClickListener(v -> copySshCommand());
    LinearLayout commandRow = row("连接命令", copy);
    sshCommand = subtitle(commandRow);
    sshCommand.setTypeface(Typeface.MONOSPACE);
    sshCommand.setTextIsSelectable(true);
    commandRow.setOnClickListener(v -> copySshCommand());
    list.addView(commandRow);

    LinearLayout keysRow = row("添加公钥", null);
    sshKeys = subtitle(keysRow);
    keysRow.setOnClickListener(v -> addPublicKey());
    list.addView(keysRow);

    LinearLayout passwordRow = row("设置 root 密码", null);
    sshPassword = subtitle(passwordRow);
    passwordRow.setOnClickListener(v -> setRootPassword());
    list.addView(passwordRow);

    LinearLayout logRow = row("查看 SSH 日志", null);
    subtitle(logRow).setText("sshd 的启动信息和登录记录");
    logRow.setOnClickListener(v -> showSshLog());
    list.addView(logRow);

    TextView note = text(
        "手机开着代理 VPN 时，局域网可能连不上：VPN 会把 UbuntuBox 的回包也送进隧道。"
            + "可在 VPN 应用里把 UbuntuBox 加入绕过列表，或开启「绕过局域网」。",
        12, 0xFF9E9E9E);
    note.setPadding(0, dp(4), 0, 0);
    list.addView(note);
    updateSshUi();
  }

  private void updateSshUi() {
    if (sshButton == null) return;
    boolean running = service != null && service.isSshRunning();
    if (!sshInstalled) {
      sshState.setText("未安装 openssh-server");
      sshButton.setText("安装");
      sshButton.setTextColor(0xFFE95420);
    } else if (running) {
      sshState.setText("运行中");
      sshButton.setText("停止");
      sshButton.setTextColor(0xFFE57373);
    } else {
      sshState.setText("已停止");
      sshButton.setText("启动");
      sshButton.setTextColor(0xFFE95420);
    }
    List<String> addresses = lanAddresses();
    StringBuilder commands = new StringBuilder();
    for (String address : addresses) {
      if (commands.length() > 0) commands.append('\n');
      commands.append(sshCommandFor(address));
    }
    sshCommand.setText(addresses.isEmpty() ? "未连接 Wi-Fi，暂无局域网地址" : commands);
    sshKeys.setText(sshKeyCount > 0
        ? "已有 " + sshKeyCount + " 个公钥（/root/.ssh/authorized_keys）"
        : "粘贴电脑上的 ~/.ssh/id_ed25519.pub，用公钥免密登录（推荐）");
    sshPassword.setText(sshHasPassword ? "已设置，可用密码登录" : "未设置，只能用公钥登录");
  }

  private static String sshCommandFor(String address) {
    return "ssh root@" + address + " -p " + TerminalService.SSH_PORT;
  }

  /** Wi-Fi / 以太网 / 热点上的 IPv4 地址，不含蜂窝和 VPN 隧道。 */
  private static List<String> lanAddresses() {
    List<String> result = new ArrayList<>();
    try {
      for (java.net.NetworkInterface nif :
          java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
        String name = nif.getName();
        if (!nif.isUp() || nif.isLoopback()
            || !(name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")
                || name.startsWith("swlan") || name.startsWith("rndis") || name.startsWith("usb")))
          continue;
        for (java.net.InetAddress address : java.util.Collections.list(nif.getInetAddresses()))
          if (address instanceof java.net.Inet4Address) result.add(address.getHostAddress());
      }
    } catch (java.net.SocketException ignored) {
      // 没有网络接口时返回空列表
    }
    return result;
  }

  private void toggleSsh() {
    if (!sshInstalled) {
      runInTerminal("安装 SSH 服务", "ubox-install apt openssh-server");
      return;
    }
    if (service == null) return;
    if (service.isSshRunning()) {
      service.stopSsh();
      updateSshUi();
      return;
    }
    try {
      service.startSsh();
    } catch (java.io.IOException error) {
      Toast.makeText(this, "启动失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
    }
    // sshd 启动需要一两秒，之后检查它是否还活着
    sshState.setText("启动中…");
    sshState.postDelayed(
        () -> {
          updateSshUi();
          if (service != null && !service.isSshRunning()) showSshLog();
          else if (sshKeyCount == 0 && !sshHasPassword)
            Toast.makeText(this, "SSH 已启动，请先添加公钥或设置密码", Toast.LENGTH_LONG).show();
        },
        2500);
  }

  private void copySshCommand() {
    List<String> addresses = lanAddresses();
    if (addresses.isEmpty()) {
      Toast.makeText(this, "未连接 Wi-Fi", Toast.LENGTH_SHORT).show();
      return;
    }
    String command = sshCommandFor(addresses.get(0));
    getSystemService(android.content.ClipboardManager.class)
        .setPrimaryClip(android.content.ClipData.newPlainText("ssh", command));
    Toast.makeText(this, "已复制：" + command, Toast.LENGTH_SHORT).show();
  }

  private void addPublicKey() {
    android.widget.EditText input = new android.widget.EditText(this);
    input.setHint("ssh-ed25519 AAAA... user@host");
    input.setTypeface(Typeface.MONOSPACE);
    input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
    input.setMinLines(3);
    android.content.ClipData clip =
        getSystemService(android.content.ClipboardManager.class).getPrimaryClip();
    if (clip != null && clip.getItemCount() > 0) {
      CharSequence copied = clip.getItemAt(0).coerceToText(this);
      if (copied != null && copied.toString().trim().matches("^(ssh-|ecdsa-|sk-)\\S+ \\S+.*"))
        input.setText(copied.toString().trim());
    }
    new AlertDialog.Builder(this)
        .setTitle("添加公钥")
        .setMessage("在电脑上运行 cat ~/.ssh/id_ed25519.pub，把输出粘贴到这里（每行一个）。")
        .setView(input)
        .setNegativeButton("取消", null)
        .setPositiveButton("添加", (d, w) -> savePublicKeys(input.getText().toString()))
        .show();
  }

  private void savePublicKeys(String text) {
    StringBuilder keys = new StringBuilder();
    for (String line : text.split("\n")) {
      line = line.trim();
      if (line.matches("^(ssh-(ed25519|rsa|dss)|ecdsa-sha2-\\S+|sk-\\S+) \\S+.*"))
        keys.append(line).append('\n');
    }
    if (keys.length() == 0) {
      Toast.makeText(this, "没有识别到公钥（应以 ssh-ed25519 / ssh-rsa / ecdsa- 开头）", Toast.LENGTH_LONG).show();
      return;
    }
    java.io.File ssh = new java.io.File(new Rootfs(this).rootfs, "root/.ssh");
    java.io.File file = new java.io.File(ssh, "authorized_keys");
    try {
      ssh.mkdirs();
      android.system.Os.chmod(ssh.getAbsolutePath(), 0700);
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(file, true)) {
        out.write(keys.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      android.system.Os.chmod(file.getAbsolutePath(), 0600);
      Toast.makeText(this, "已添加", Toast.LENGTH_SHORT).show();
    } catch (java.io.IOException | android.system.ErrnoException error) {
      Toast.makeText(this, "写入失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
    }
    refreshStatus();
  }

  private void setRootPassword() {
    android.widget.EditText input = new android.widget.EditText(this);
    input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    input.setHint("至少 6 位");
    new AlertDialog.Builder(this)
        .setTitle("设置 root 密码")
        .setMessage("设置后可以用 ssh 密码登录，同一局域网内的设备都能尝试连接，请使用强密码。")
        .setView(input)
        .setNegativeButton("取消", null)
        .setPositiveButton("保存", (d, w) -> savePassword(input.getText().toString()))
        .show();
  }

  private void savePassword(String password) {
    if (password.length() < 6 || password.contains("\n") || password.contains(":")) {
      Toast.makeText(this, "密码至少 6 位，且不能包含冒号或换行", Toast.LENGTH_LONG).show();
      return;
    }
    // base64 传入，避免密码中的特殊字符被 shell 解释
    String encoded = android.util.Base64.encodeToString(
        ("root:" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8),
        android.util.Base64.NO_WRAP);
    new Thread(
            () -> {
              String message;
              try {
                String output = proot.run(
                    "printf %s " + encoded + " | base64 -d | chpasswd && echo ok", 20_000);
                message = output.trim().endsWith("ok") ? "密码已设置" : "设置失败：" + output.trim();
              } catch (Exception error) {
                message = "设置失败：" + error.getMessage();
              }
              String toast = message;
              runOnUiThread(() -> {
                Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
                refreshStatus();
              });
            },
            "settings-password")
        .start();
  }

  private void showSshLog() {
    String log = "";
    if (service != null) {
      java.io.File file = service.sshLog();
      try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(file, "r")) {
        long start = Math.max(0, in.length() - 8192);
        byte[] bytes = new byte[(int) (in.length() - start)];
        in.seek(start);
        in.readFully(bytes);
        log = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
      } catch (java.io.IOException missing) {
        log = "";
      }
    }
    TextView view = text(log.isEmpty() ? "（暂无日志）" : log, 11, Color.WHITE);
    view.setTypeface(Typeface.MONOSPACE);
    view.setTextIsSelectable(true);
    view.setPadding(dp(16), dp(8), dp(16), dp(8));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(view);
    new AlertDialog.Builder(this).setTitle("SSH 日志").setView(scroll).setPositiveButton("关闭", null).show();
  }

  private TextView subtitle(LinearLayout row) {
    return (TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1);
  }

  // ---------------------------------------------------------------- 视图

  private TextView header(String title) {
    TextView view = text(title, 13, 0xFFE95420);
    view.setTypeface(Typeface.DEFAULT_BOLD);
    view.setPadding(0, dp(24), 0, dp(4));
    return view;
  }

  private LinearLayout row(String title, View action) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(10), 0, dp(10));
    TypedValue ripple = new TypedValue();
    getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
    row.setBackgroundResource(ripple.resourceId);

    LinearLayout texts = new LinearLayout(this);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.addView(text(title, 16, Color.WHITE));
    texts.addView(text("", 13, 0xFF9E9E9E));
    row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    if (action != null) row.addView(action);
    return row;
  }

  private Button actionButton() {
    Button button = new Button(this, null, 0, android.R.style.Widget_DeviceDefault_Button_Borderless);
    button.setAllCaps(false);
    button.setText("…");
    button.setTextColor(0xFF9E9E9E);
    return button;
  }

  private static void styleButton(Button button, boolean isInstalled) {
    button.setText(isInstalled ? "已安装 ✓" : "安装");
    button.setTextColor(isInstalled ? 0xFF81C784 : 0xFFE95420);
  }

  private TextView text(String value, int sp, int color) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    view.setTextColor(color);
    return view;
  }

  private int dp(int value) {
    return (int) (value * density);
  }
}
