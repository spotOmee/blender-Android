/* SPDX-FileCopyrightText: 2026 Blender Authors
 *
 * SPDX-License-Identifier: GPL-2.0-or-later */

package org.blender.blender;

import android.app.Dialog;
import android.app.NativeActivity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.system.Os;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * NativeActivity subclass for Blender. Extracts the bundled runtime
 * (Python + scripts + datafiles + MCP AI Bridge) on first launch, then loads libblender.so
 * and bridges soft-keyboard IME text to native. Landscape only.
 */
public class BlenderActivity extends NativeActivity {

  /* NativeActivity dlopen()s the library from native code, which never registers
   * it with the class loader, so the JNI lookup for the native methods below
   * fails with UnsatisfiedLinkError. Load it here as well to register it. */
  static {
    System.loadLibrary("blender");
  }

  /* Must match GHOST_SystemPathsAndroid: <filesDir>/blender/<version>. */
  private static final String VERSION = "5.3";
  private static final String RUNTIME_ZIP = "blender_runtime.zip";
  private static final String PYTHON_VERSION = "3.13";
  private static final String PYTHON_FULL_VERSION = "3.13.13";
  private static final String PYTHON_BIN_LIB = "libpython3_13_bin.so";
  private static final String TAG = "Blender";

  /* MCP Server Preferences & Port */
  private static final String MCP_PREF_NAME = "blender_mcp_prefs";
  private static final String PREF_IS_MCP_CONFIGURED = "is_mcp_configured";
  private static final int MCP_DEFAULT_PORT = 9876;

  private InputView inputView;
  private TextView mcpOverlayBadge;
  private PowerManager.WakeLock mcpWakeLock = null;

  private native void nativeOnCommitText(String text);
  private native void nativeOnKey(int keycode, int action, int metaState);
  private native void nativeSetLogPath(String path);
  private native void nativeOpenMainFile(String path);

  private String logPath = null;

  @Override
  protected void onCreate(Bundle state) {
    /* Runtime files must exist before native Blender init reads them. */
    extractRuntimeIfNeeded();
    setUpPythonInterpreter();
    /* Before super.onCreate: that is what starts the native activity, and the
     * log is opened as the first thing it does. */
    chooseLogPath();
    publishHardwareNames();
    /* Also before super.onCreate: the native side reads it while building argv. */
    publishLaunchFile(getIntent());
    super.onCreate(state);
    announceLogPath();

    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    enterImmersive();
    requestAllFilesAccess();
    acquireMcpWakeLock();

    inputView = new InputView(this);
    addContentView(inputView, new ViewGroup.LayoutParams(1, 1));

    setupMcpUi();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    releaseMcpWakeLock();
  }

  /* =========================================================================
   * MCP Server (Model Context Protocol) Integration & UI Overlays
   * ========================================================================= */

  private void setupMcpUi() {
    setupMcpOverlayBadge();

    SharedPreferences prefs = getSharedPreferences(MCP_PREF_NAME, Context.MODE_PRIVATE);
    boolean isConfigured = prefs.getBoolean(PREF_IS_MCP_CONFIGURED, false);

    if (!isConfigured) {
      /* First launch onboarding: automatically display welcome modal. */
      getWindow().getDecorView().postDelayed(() -> showMcpOnboardingDialog(true), 800);
    }
  }

  /**
   * Floating semi-transparent "⚡ MCP" badge on top-right of screen.
   * Can be tapped to open settings or dragged to avoid obscuring viewport buttons.
   */
  private void setupMcpOverlayBadge() {
    FrameLayout container = new FrameLayout(this);

    mcpOverlayBadge = new TextView(this);
    mcpOverlayBadge.setText("⚡ MCP");
    mcpOverlayBadge.setTypeface(Typeface.DEFAULT_BOLD);
    mcpOverlayBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
    mcpOverlayBadge.setTextColor(Color.WHITE);

    GradientDrawable bg = new GradientDrawable();
    bg.setCornerRadius(dpToPx(16));
    bg.setColor(0xCC1E1E1E);
    bg.setStroke(dpToPx(1.5f), 0xFF4CAF50);
    mcpOverlayBadge.setBackground(bg);

    int padH = dpToPx(10);
    int padV = dpToPx(6);
    mcpOverlayBadge.setPadding(padH, padV, padH, padV);

    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    );
    lp.gravity = Gravity.TOP | Gravity.END;
    lp.topMargin = dpToPx(14);
    lp.rightMargin = dpToPx(14);
    mcpOverlayBadge.setLayoutParams(lp);

    // Draggable and clickable touch listener
    mcpOverlayBadge.setOnTouchListener(new View.OnTouchListener() {
      private float startX, startY;
      private float initialX, initialY;
      private boolean isClick = true;

      @Override
      public boolean onTouch(View v, MotionEvent event) {
        switch (event.getAction()) {
          case MotionEvent.ACTION_DOWN:
            startX = event.getRawX();
            startY = event.getRawY();
            initialX = v.getX();
            initialY = v.getY();
            isClick = true;
            return true;
          case MotionEvent.ACTION_MOVE:
            float dx = event.getRawX() - startX;
            float dy = event.getRawY() - startY;
            if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
              isClick = false;
              v.setX(initialX + dx);
              v.setY(initialY + dy);
            }
            return true;
          case MotionEvent.ACTION_UP:
            if (isClick) {
              showMcpSettingsDialog();
            }
            return true;
        }
        return false;
      }
    });

    container.addView(mcpOverlayBadge);
    addContentView(container, new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    ));
  }

  /**
   * First-Launch Onboarding Dialog & Setup Guide.
   */
  private void showMcpOnboardingDialog(boolean isFirstLaunch) {
    Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawable(new ColorDrawable(0xEE161616));
    }

    ScrollView scrollView = new ScrollView(this);
    scrollView.setFillViewport(true);

    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(dpToPx(22), dpToPx(20), dpToPx(22), dpToPx(20));

    // Header
    TextView titleView = new TextView(this);
    titleView.setText("⚡ Blender AI Bridge (Model Context Protocol)");
    titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
    titleView.setTypeface(Typeface.DEFAULT_BOLD);
    titleView.setTextColor(Color.WHITE);
    layout.addView(titleView);

    TextView subtitleView = new TextView(this);
    subtitleView.setText("Control Blender on this Android device with AI coding assistants (Claude Desktop, Cursor, ChatGPT, etc.).");
    subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
    subtitleView.setTextColor(0xFFBBBBBB);
    subtitleView.setPadding(0, dpToPx(4), 0, dpToPx(14));
    layout.addView(subtitleView);

    // Live Server Info Card
    String deviceIp = getDeviceIpAddress();
    LinearLayout statusCard = createCardLayout();
    TextView statusText = new TextView(this);
    statusText.setText("🟢 MCP Server Auto-Started (Port " + MCP_DEFAULT_PORT + " on 0.0.0.0)");
    statusText.setTextColor(0xFF4CAF50);
    statusText.setTypeface(Typeface.DEFAULT_BOLD);
    statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);

    TextView ipText = new TextView(this);
    ipText.setText("• Device Wi-Fi IP: " + deviceIp + ":" + MCP_DEFAULT_PORT + "\n• Local USB Forward: 127.0.0.1:" + MCP_DEFAULT_PORT);
    ipText.setTextColor(0xFFDDDDDD);
    ipText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
    ipText.setPadding(0, dpToPx(4), 0, 0);

    statusCard.addView(statusText);
    statusCard.addView(ipText);
    layout.addView(statusCard);

    // Guide Option 1: USB Connection
    TextView method1Title = createSectionHeader("Method 1: USB Cable (Recommended & Lowest Latency)");
    layout.addView(method1Title);

    TextView method1Desc = new TextView(this);
    method1Desc.setText("1. Connect device via USB and run in your computer terminal:\n    adb reverse tcp:" + MCP_DEFAULT_PORT + " tcp:" + MCP_DEFAULT_PORT + "\n2. Add this to claude_desktop_config.json:");
    method1Desc.setTextColor(0xFFCCCCCC);
    method1Desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
    method1Desc.setPadding(0, 0, 0, dpToPx(6));
    layout.addView(method1Desc);

    String usbConfigJson = "{\n"
        + "  \"mcpServers\": {\n"
        + "    \"blender\": {\n"
        + "      \"command\": \"uvx\",\n"
        + "      \"args\": [\"mcp-for-blender\"]\n"
        + "    }\n"
        + "  }\n"
        + "}";
    layout.addView(createCodeBlock(usbConfigJson));

    // Guide Option 2: Wi-Fi Wireless Connection
    TextView method2Title = createSectionHeader("Method 2: Wi-Fi LAN (Wireless)");
    layout.addView(method2Title);

    TextView method2Desc = new TextView(this);
    method2Desc.setText("Connect directly over Wi-Fi without USB cables. Add this to your MCP config:");
    method2Desc.setTextColor(0xFFCCCCCC);
    method2Desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
    method2Desc.setPadding(0, 0, 0, dpToPx(6));
    layout.addView(method2Desc);

    String wifiConfigJson = "{\n"
        + "  \"mcpServers\": {\n"
        + "    \"blender\": {\n"
        + "      \"command\": \"uvx\",\n"
        + "      \"args\": [\"mcp-for-blender\", \"--host\", \"" + deviceIp + "\", \"--port\", \"" + MCP_DEFAULT_PORT + "\"]\n"
        + "    }\n"
        + "  }\n"
        + "}";
    layout.addView(createCodeBlock(wifiConfigJson));

    // Button Row: Quick Copy Actions
    LinearLayout copyBtnRow = new LinearLayout(this);
    copyBtnRow.setOrientation(LinearLayout.HORIZONTAL);
    copyBtnRow.setPadding(0, dpToPx(12), 0, dpToPx(8));

    Button copyUsbBtn = createSecondaryButton("📋 Copy USB Config");
    copyUsbBtn.setOnClickListener(v -> copyToClipboard("MCP USB Config", usbConfigJson));

    Button copyWifiBtn = createSecondaryButton("📋 Copy Wi-Fi Config");
    copyWifiBtn.setOnClickListener(v -> copyToClipboard("MCP Wi-Fi Config", wifiConfigJson));

    Button copyAdbBtn = createSecondaryButton("⚡ Copy ADB Command");
    copyAdbBtn.setOnClickListener(v -> copyToClipboard("ADB Command", "adb reverse tcp:" + MCP_DEFAULT_PORT + " tcp:" + MCP_DEFAULT_PORT));

    copyBtnRow.addView(copyUsbBtn);
    copyBtnRow.addView(copyWifiBtn);
    copyBtnRow.addView(copyAdbBtn);
    layout.addView(copyBtnRow);

    // Primary Action Button (Done / Continue)
    Button doneBtn = new Button(this);
    doneBtn.setText(isFirstLaunch ? "🚀 Done / Continue to Blender" : "✖️ Close");
    doneBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    doneBtn.setTypeface(Typeface.DEFAULT_BOLD);
    doneBtn.setTextColor(Color.WHITE);

    GradientDrawable btnBg = new GradientDrawable();
    btnBg.setCornerRadius(dpToPx(8));
    btnBg.setColor(0xFF007ACC);
    doneBtn.setBackground(btnBg);
    doneBtn.setPadding(0, dpToPx(12), 0, dpToPx(12));

    LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    );
    doneLp.topMargin = dpToPx(10);
    doneBtn.setLayoutParams(doneLp);

    doneBtn.setOnClickListener(v -> {
      if (isFirstLaunch) {
        SharedPreferences prefs = getSharedPreferences(MCP_PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_IS_MCP_CONFIGURED, true).apply();
        Toast.makeText(this, "MCP Server configured! Tap ⚡ MCP anytime for settings.", Toast.LENGTH_SHORT).show();
      }
      dialog.dismiss();
    });
    layout.addView(doneBtn);

    scrollView.addView(layout);
    dialog.setContentView(scrollView);

    dialog.setOnDismissListener(d -> enterImmersive());
    dialog.show();

    if (window != null) {
      int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
      int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.92f);
      window.setLayout(width, maxHeight);
    }
  }

  /**
   * Persistent MCP Server Settings dialog, accessible anytime.
   */
  private void showMcpSettingsDialog() {
    Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawable(new ColorDrawable(0xEE1E1E1E));
    }

    ScrollView scrollView = new ScrollView(this);
    scrollView.setFillViewport(true);

    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(dpToPx(20), dpToPx(18), dpToPx(20), dpToPx(18));

    TextView title = new TextView(this);
    title.setText("⚡ MCP Server Settings");
    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    title.setTextColor(Color.WHITE);
    layout.addView(title);

    // Status Row
    LinearLayout card = createCardLayout();
    TextView statusView = new TextView(this);
    statusView.setText("Checking MCP server status...");
    statusView.setTextColor(0xFFFFA000);
    statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
    card.addView(statusView);

    String deviceIp = getDeviceIpAddress();
    TextView detailsView = new TextView(this);
    detailsView.setText("Host: 0.0.0.0 (All interfaces)\nPort: " + MCP_DEFAULT_PORT + "\nDevice IP: " + deviceIp);
    detailsView.setTextColor(0xFFCCCCCC);
    detailsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
    detailsView.setPadding(0, dpToPx(6), 0, 0);
    card.addView(detailsView);

    layout.addView(card);

    // Check socket responsiveness asynchronously
    new Thread(() -> {
      boolean listening = isMcpListening(MCP_DEFAULT_PORT);
      runOnUiThread(() -> {
        if (listening) {
          statusView.setText("🟢 MCP Server Running (Listening on Port " + MCP_DEFAULT_PORT + ")");
          statusView.setTextColor(0xFF4CAF50);
          if (mcpOverlayBadge != null) {
            mcpOverlayBadge.setText("⚡ MCP [ON]");
          }
        } else {
          statusView.setText("🔴 MCP Server Inactive or Starting");
          statusView.setTextColor(0xFFF44336);
          if (mcpOverlayBadge != null) {
            mcpOverlayBadge.setText("⚡ MCP [OFF]");
          }
        }
      });
    }).start();

    // Buttons
    LinearLayout actionsRow = new LinearLayout(this);
    actionsRow.setOrientation(LinearLayout.VERTICAL);
    actionsRow.setPadding(0, dpToPx(10), 0, 0);

    Button restartBtn = createStyledButton("🔄 Restart MCP Service", 0xFFE65100);
    restartBtn.setOnClickListener(v -> {
      triggerMcpRestart();
      Toast.makeText(this, "MCP service reload triggered", Toast.LENGTH_SHORT).show();
      dialog.dismiss();
    });

    Button copyAdbBtn = createStyledButton("⚡ Copy ADB Reverse Command", 0xFF333333);
    copyAdbBtn.setOnClickListener(v -> copyToClipboard("ADB Command", "adb reverse tcp:" + MCP_DEFAULT_PORT + " tcp:" + MCP_DEFAULT_PORT));

    Button copyConfigBtn = createStyledButton("📋 Copy MCP Config JSON", 0xFF333333);
    String configSnippet = "{\n  \"mcpServers\": {\n    \"blender\": {\n      \"command\": \"uvx\",\n      \"args\": [\"mcp-for-blender\", \"--host\", \"" + deviceIp + "\", \"--port\", \"" + MCP_DEFAULT_PORT + "\"]\n    }\n  }\n}";
    copyConfigBtn.setOnClickListener(v -> copyToClipboard("MCP Config JSON", configSnippet));

    Button guideBtn = createStyledButton("📖 Open Setup Guide & Instructions", 0xFF007ACC);
    guideBtn.setOnClickListener(v -> {
      dialog.dismiss();
      showMcpOnboardingDialog(false);
    });

    Button closeBtn = createStyledButton("✖️ Close", 0xFF222222);
    closeBtn.setOnClickListener(v -> dialog.dismiss());

    actionsRow.addView(restartBtn);
    actionsRow.addView(copyAdbBtn);
    actionsRow.addView(copyConfigBtn);
    actionsRow.addView(guideBtn);
    actionsRow.addView(closeBtn);
    layout.addView(actionsRow);

    scrollView.addView(layout);
    dialog.setContentView(scrollView);

    dialog.setOnDismissListener(d -> enterImmersive());
    dialog.show();

    if (window != null) {
      int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.75f);
      window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
  }

  private void triggerMcpRestart() {
    new Thread(() -> {
      try (Socket s = new Socket()) {
        s.connect(new InetSocketAddress("127.0.0.1", MCP_DEFAULT_PORT), 400);
        OutputStream out = s.getOutputStream();
        String cmd = "{\"type\": \"restart_server\"}\n";
        out.write(cmd.getBytes("UTF-8"));
        out.flush();
      } catch (Exception ignored) {
        // If stopped, startup handler will automatically re-bind
      }
    }).start();
  }

  private boolean isMcpListening(int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private String getDeviceIpAddress() {
    try {
      List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
      for (NetworkInterface intf : interfaces) {
        if (intf.isLoopback() || !intf.isUp()) continue;
        List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
        for (InetAddress addr : addrs) {
          if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
            return addr.getHostAddress();
          }
        }
      }
    } catch (Exception ignored) {
    }
    return "127.0.0.1";
  }

  private void copyToClipboard(String label, String text) {
    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
    ClipData clip = ClipData.newPlainText(label, text);
    if (clipboard != null) {
      clipboard.setPrimaryClip(clip);
      Toast.makeText(this, "Copied to clipboard: " + label, Toast.LENGTH_SHORT).show();
    }
  }

  private void acquireMcpWakeLock() {
    try {
      if (mcpWakeLock == null) {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
          mcpWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "blender:mcp_service");
          mcpWakeLock.acquire(4 * 60 * 60 * 1000L); // 4-hour lease
        }
      }
    } catch (Exception ex) {
      Log.w(TAG, "Cannot acquire wake lock for MCP background listener", ex);
    }
  }

  private void releaseMcpWakeLock() {
    try {
      if (mcpWakeLock != null && mcpWakeLock.isHeld()) {
        mcpWakeLock.release();
        mcpWakeLock = null;
      }
    } catch (Exception ignored) {
    }
  }

  /* UI Helpers */

  private int dpToPx(float dp) {
    return (int) TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp,
        getResources().getDisplayMetrics()
    );
  }

  private LinearLayout createCardLayout() {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
    GradientDrawable bg = new GradientDrawable();
    bg.setCornerRadius(dpToPx(8));
    bg.setColor(0xFF282828);
    bg.setStroke(dpToPx(1), 0xFF3E3E3E);
    card.setBackground(bg);
    return card;
  }

  private TextView createSectionHeader(String text) {
    TextView tv = new TextView(this);
    tv.setText(text);
    tv.setTypeface(Typeface.DEFAULT_BOLD);
    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
    tv.setTextColor(0xFF64B5F6);
    tv.setPadding(0, dpToPx(12), 0, dpToPx(4));
    return tv;
  }

  private View createCodeBlock(String code) {
    TextView tv = new TextView(this);
    tv.setText(code);
    tv.setTypeface(Typeface.MONOSPACE);
    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
    tv.setTextColor(0xFF81C784);
    tv.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8));

    GradientDrawable bg = new GradientDrawable();
    bg.setCornerRadius(dpToPx(6));
    bg.setColor(0xFF1E1E1E);
    bg.setStroke(dpToPx(1), 0xFF333333);
    tv.setBackground(bg);
    return tv;
  }

  private Button createSecondaryButton(String text) {
    Button btn = new Button(this);
    btn.setText(text);
    btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
    btn.setTextColor(Color.WHITE);

    GradientDrawable bg = new GradientDrawable();
    bg.setCornerRadius(dpToPx(6));
    bg.setColor(0xFF333333);
    btn.setBackground(bg);

    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
    );
    lp.rightMargin = dpToPx(4);
    btn.setLayoutParams(lp);
    return btn;
  }

  private Button createStyledButton(String text, int bgColor) {
    Button btn = new Button(this);
    btn.setText(text);
    btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
    btn.setTextColor(Color.WHITE);
    btn.setTypeface(Typeface.DEFAULT_BOLD);

    GradientDrawable bg = new GradientDrawable();
    bg.setCornerRadius(dpToPx(6));
    bg.setColor(bgColor);
    btn.setBackground(bg);

    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    );
    lp.topMargin = dpToPx(6);
    btn.setLayoutParams(lp);
    return btn;
  }

  /* =========================================================================
   * Original Blender Activity Core Logic
   * ========================================================================= */

  private void requestAllFilesAccess() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
      return;
    }
    try {
      Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                 Uri.parse("package:" + getPackageName()));
      startActivity(intent);
    }
    catch (Exception ex) {
      try {
        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
      }
      catch (Exception ignored) {
      }
    }
  }

  private void publishHardwareNames() {
    try {
      String device = joinNonEmpty(capitalize(Build.MANUFACTURER), Build.MODEL);
      if (!device.isEmpty()) {
        Os.setenv("BLENDER_ANDROID_DEVICE", device, true);
      }
      String vendor = Build.SOC_MANUFACTURER;
      if ("QTI".equalsIgnoreCase(vendor)) {
        vendor = "Qualcomm";
      }
      String soc = joinNonEmpty(vendor, Build.SOC_MODEL);
      if (!soc.isEmpty()) {
        Os.setenv("BLENDER_ANDROID_SOC", soc, true);
      }
    }
    catch (Exception ex) {
      Log.w(TAG, "hardware names unavailable", ex);
    }
  }

  private static String capitalize(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    return Character.toUpperCase(text.charAt(0)) + text.substring(1);
  }

  private static String joinNonEmpty(String a, String b) {
    StringBuilder out = new StringBuilder();
    for (String part : new String[] {a, b}) {
      if (part == null) {
        continue;
      }
      part = part.trim();
      if (part.isEmpty() || part.equalsIgnoreCase(Build.UNKNOWN)) {
        continue;
      }
      if (out.length() != 0) {
        out.append(' ');
      }
      out.append(part);
    }
    return out.toString();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    String path = resolveBlendPath(intent);
    if (path != null) {
      nativeOpenMainFile(path);
    }
  }

  private void publishLaunchFile(Intent intent) {
    String path = resolveBlendPath(intent);
    if (path == null) {
      return;
    }
    try {
      Os.setenv("BLENDER_ANDROID_OPEN_FILE", path, true);
    }
    catch (Exception ex) {
      Log.w(TAG, "cannot publish launch file", ex);
    }
  }

  private String resolveBlendPath(Intent intent) {
    if (intent == null) {
      return null;
    }
    String action = intent.getAction();
    if (!Intent.ACTION_VIEW.equals(action) && !Intent.ACTION_EDIT.equals(action)) {
      return null;
    }
    Uri uri = intent.getData();
    if (uri == null) {
      return null;
    }
    try {
      String path = resolveUriToPath(uri);
      Log.i(TAG, "open request " + uri + " -> " + path);
      return path;
    }
    catch (Exception ex) {
      Log.w(TAG, "cannot resolve " + uri, ex);
      return null;
    }
  }

  private String resolveUriToPath(Uri uri) throws Exception {
    if ("file".equals(uri.getScheme())) {
      String path = usable(uri.getPath());
      if (path != null) {
        return path;
      }
    }

    if (DocumentsContract.isDocumentUri(this, uri) &&
        "com.android.externalstorage.documents".equals(uri.getAuthority()))
    {
      String[] id = DocumentsContract.getDocumentId(uri).split(":", 2);
      if (id.length == 2) {
        File root = "primary".equalsIgnoreCase(id[0]) ? Environment.getExternalStorageDirectory() :
                                                        volumeRoot(id[0]);
        if (root != null) {
          String path = usable(new File(root, id[1]).getAbsolutePath());
          if (path != null) {
            return path;
          }
        }
      }
    }

    if ("content".equals(uri.getScheme())) {
      try (Cursor c = getContentResolver().query(
               uri, new String[] {MediaStore.MediaColumns.DATA}, null, null, null))
      {
        if (c != null && c.moveToFirst() && !c.isNull(0)) {
          String path = usable(c.getString(0));
          if (path != null) {
            return path;
          }
        }
      }
      catch (Exception ignored) {
      }
    }

    try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
      if (pfd != null) {
        String path = usable(Os.readlink("/proc/self/fd/" + pfd.getFd()));
        if (path != null) {
          return path;
        }
      }
    }
    catch (Exception ignored) {
    }

    return copyToCache(uri);
  }

  private static String usable(String path) {
    if (path == null) {
      return null;
    }
    File file = new File(path);
    return (file.isFile() && file.canRead()) ? file.getAbsolutePath() : null;
  }

  private File volumeRoot(String uuid) {
    try {
      StorageManager sm = (StorageManager)getSystemService(Context.STORAGE_SERVICE);
      for (StorageVolume volume : sm.getStorageVolumes()) {
        if (uuid.equalsIgnoreCase(volume.getUuid())) {
          return volume.getDirectory();
        }
      }
    }
    catch (Exception ignored) {
    }
    return null;
  }

  private String copyToCache(Uri uri) throws Exception {
    File dir = new File(getCacheDir(), "opened");
    dir.mkdirs();
    File out = new File(dir, displayName(uri));
    try (InputStream is = getContentResolver().openInputStream(uri);
         OutputStream os = new FileOutputStream(out))
    {
      if (is == null) {
        return null;
      }
      byte[] buf = new byte[65536];
      int n;
      while ((n = is.read(buf)) > 0) {
        os.write(buf, 0, n);
      }
    }
    Log.w(TAG, "no path behind " + uri + "; opening a copy, relative links will not resolve");
    return out.getAbsolutePath();
  }

  private String displayName(Uri uri) {
    String name = null;
    try (Cursor c = getContentResolver().query(
             uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null))
    {
      if (c != null && c.moveToFirst() && !c.isNull(0)) {
        name = c.getString(0);
      }
    }
    catch (Exception ignored) {
    }
    if (name == null || name.isEmpty()) {
      name = "opened.blend";
    }
    name = new File(name).getName().replace("..", "_");
    return name.toLowerCase().endsWith(".blend") ? name : name + ".blend";
  }

  public boolean openUrl(String url) {
    try {
      Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(intent);
      return true;
    }
    catch (Exception ex) {
      Log.w(TAG, "cannot open " + url, ex);
      return false;
    }
  }

  private void enterImmersive() {
    View d = getWindow().getDecorView();
    d.setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        | View.SYSTEM_UI_FLAG_FULLSCREEN
        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) {
      enterImmersive();
    }
  }

  private void setUpPythonInterpreter() {
    File pythonHome = new File(getFilesDir(), "blender/" + VERSION + "/python");
    if (!pythonHome.isDirectory()) {
      return;
    }
    try {
      File interpreter = new File(getApplicationInfo().nativeLibraryDir, PYTHON_BIN_LIB);
      if (!interpreter.isFile()) {
        return;
      }
      File binDir = new File(pythonHome, "bin");
      binDir.mkdirs();
      File link = new File(binDir, "python" + PYTHON_VERSION);
      link.delete();
      Os.symlink(interpreter.getAbsolutePath(), link.getAbsolutePath());

      Os.setenv("PYTHONHOME", pythonHome.getAbsolutePath(), true);

      writeText(new File(pythonHome, "pyvenv.cfg"),
          "home = " + binDir.getAbsolutePath() + "\n"
              + "include-system-site-packages = true\n"
              + "version = " + PYTHON_FULL_VERSION + "\n");

      Os.setenv("LD_LIBRARY_PATH", getApplicationInfo().nativeLibraryDir, true);

      File certs = new File(pythonHome,
          "lib/python" + PYTHON_VERSION + "/site-packages/certifi/cacert.pem");
      if (certs.isFile()) {
        Os.setenv("SSL_CERT_FILE", certs.getAbsolutePath(), true);
      }
    }
    catch (Exception ex) {
      Log.w(TAG, "python interpreter setup failed", ex);
    }
  }

  private static String readText(File f) {
    InputStream in = null;
    try {
      in = new java.io.FileInputStream(f);
      byte[] buffer = new byte[64];
      int n = in.read(buffer);
      return n > 0 ? new String(buffer, 0, n, "UTF-8").trim() : "";
    }
    catch (Exception ex) {
      return "";
    }
    finally {
      if (in != null) {
        try {
          in.close();
        }
        catch (Exception ignored) {
        }
      }
    }
  }

  private static void deleteTree(File f) {
    File[] children = f.listFiles();
    if (children != null) {
      for (File child : children) {
        deleteTree(child);
      }
    }
    f.delete();
  }

  private static void writeText(File f, String text) {
    OutputStream out = null;
    try {
      out = new FileOutputStream(f);
      out.write(text.getBytes("UTF-8"));
    }
    catch (Exception ex) {
      Log.w(TAG, "could not write " + f, ex);
    }
    finally {
      if (out != null) {
        try {
          out.close();
        }
        catch (Exception ignored) {
        }
      }
    }
  }

  private void chooseLogPath() {
    File[] candidates = {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        Environment.getExternalStorageDirectory(),
        getExternalFilesDir(null),
    };
    for (File dir : candidates) {
      if (dir == null) {
        continue;
      }
      dir.mkdirs();
      File candidate = new File(dir, "blender-startup.log");
      try (FileOutputStream probe = new FileOutputStream(candidate)) {
        logPath = candidate.getAbsolutePath();
        break;
      }
      catch (Exception ignored) {
      }
    }
    if (logPath != null) {
      nativeSetLogPath(logPath);
      publishOutputDir(new File(logPath).getParentFile());
    }
  }

  private void publishOutputDir(File parent) {
    File output = new File(parent, "Blender");
    output.mkdirs();
    try {
      Os.setenv("BLENDER_ANDROID_OUTPUT_DIR", output.getAbsolutePath(), true);
    }
    catch (Exception ex) {
      Log.w(TAG, "render output folder unavailable", ex);
    }
  }

  private void announceLogPath() {
    final String message = (logPath != null) ? "Log: " + logPath :
                                               "No writable location for the log";
    runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
  }

  private void extractRuntimeIfNeeded() {
    File root = new File(getFilesDir(), "blender/" + VERSION);
    File marker = new File(root, ".installed-" + VERSION);

    long stamp;
    try {
      stamp = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
    }
    catch (Exception ex) {
      stamp = 0;
    }
    String want = Long.toString(stamp);

    if (marker.isFile() && want.equals(readText(marker))) {
      ensureMcpAddonInstalled(root);
      return;
    }
    deleteTree(root);
    root.mkdirs();
    try (InputStream is = getAssets().open(RUNTIME_ZIP, AssetManager.ACCESS_STREAMING);
         ZipInputStream zis = new ZipInputStream(is)) {
      ZipEntry e;
      byte[] buf = new byte[65536];
      while ((e = zis.getNextEntry()) != null) {
        File out = new File(root, e.getName());
        if (e.isDirectory()) {
          out.mkdirs();
          continue;
        }
        File parent = out.getParentFile();
        if (parent != null) {
          parent.mkdirs();
        }
        try (OutputStream os = new FileOutputStream(out)) {
          int n;
          while ((n = zis.read(buf)) > 0) {
            os.write(buf, 0, n);
          }
        }
      }
      writeText(marker, want);
      ensureMcpAddonInstalled(root);
    }
    catch (Exception ex) {
      throw new RuntimeException("Failed to extract Blender runtime", ex);
    }
  }

  /**
   * Guarantees the MCP socket daemon addon is installed in scripts/startup/
   * so Blender loads it automatically on every start.
   */
  private void ensureMcpAddonInstalled(File root) {
    try {
      File startupDir = new File(root, "scripts/startup");
      startupDir.mkdirs();
      File addonDest = new File(startupDir, "bl_mcp_for_blender.py");
      try (InputStream in = getAssets().open("scripts/startup/bl_mcp_for_blender.py");
           OutputStream out = new FileOutputStream(addonDest)) {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
          out.write(buf, 0, n);
        }
      }
      Log.i(TAG, "Verified MCP addon in: " + addonDest);
    } catch (Exception ex) {
      Log.w(TAG, "Could not copy bundled bl_mcp_for_blender.py: " + ex.getMessage());
    }
  }

  public void showKeyboard() {
    runOnUiThread(() -> {
      inputView.setFocusableInTouchMode(true);
      inputView.requestFocus();
      InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
      imm.showSoftInput(inputView, InputMethodManager.SHOW_IMPLICIT);
    });
  }

  public void hideKeyboard() {
    runOnUiThread(() -> {
      InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
      imm.hideSoftInputFromWindow(inputView.getWindowToken(), 0);
    });
  }

  /** Invisible view whose InputConnection captures IME text. */
  private class InputView extends View {
    InputView(Context context) {
      super(context);
      setFocusable(true);
      setFocusableInTouchMode(true);
    }

    @Override
    public boolean onCheckIsTextEditor() {
      return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
      outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
      outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN;

      return new BaseInputConnection(this, false) {
        private String composing = "";

        private void replaceComposing(String text) {
          int common = 0;
          final int max = Math.min(composing.length(), text.length());
          while (common < max && composing.charAt(common) == text.charAt(common)) {
            common++;
          }
          if (common > 0 && common < text.length() &&
              Character.isLowSurrogate(text.charAt(common)))
          {
            common--;
          }
          final int stale = composing.codePointCount(common, composing.length());
          for (int i = 0; i < stale; i++) {
            nativeOnKey(KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_DOWN, 0);
            nativeOnKey(KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_UP, 0);
          }
          if (common < text.length()) {
            nativeOnCommitText(text.substring(common));
          }
          composing = text;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
          replaceComposing(text.toString());
          return true;
        }

        @Override
        public boolean finishComposingText() {
          composing = "";
          return true;
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
          replaceComposing(text.toString());
          composing = "";
          return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
          composing = "";
          nativeOnKey(event.getKeyCode(), event.getAction(), event.getMetaState());
          return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
          composing = "";
          for (int i = 0; i < beforeLength; i++) {
            nativeOnKey(KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_DOWN, 0);
            nativeOnKey(KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_UP, 0);
          }
          return true;
        }
      };
    }
  }
}
