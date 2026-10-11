package io.vaultbridge.app;

import android.app.Activity;
import android.content.Intent;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("deprecation")
public class ScanActivity extends Activity implements SurfaceHolder.Callback {
    Camera camera;
    SurfaceView surface;
    final ExecutorService decoder = Executors.newSingleThreadExecutor();
    final AtomicBoolean decoding = new AtomicBoolean();
    volatile boolean finished;
    volatile long lastFrame;
    @Override public void onCreate(Bundle state) {
        boolean systemDark=(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        UiTheme theme=UiTheme.from(new AppearanceConfig(getIntent().getBooleanExtra("appearanceDark",systemDark),getIntent().getIntExtra("appearanceAccent",AppearanceConfig.DEFAULT_ACCENT),"扫码沿用当前库外观"));setTheme(UiChrome.themeResource(this,theme.dark,false));
        super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);UiChrome.bars(this,theme);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setBackgroundColor(theme.canvas);
        if(android.os.Build.VERSION.SDK_INT>=35)layout.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        TextView caption = new TextView(this); caption.setText("对准电脑端的 VaultBridge 配对二维码\n扫码后会校验电脑身份"); caption.setTextColor(theme.ink); caption.setTextSize(16); caption.setPadding(UiChrome.dp(this,20),UiChrome.dp(this,20),UiChrome.dp(this,20),UiChrome.dp(this,16)); layout.addView(caption);
        surface = new SurfaceView(this); layout.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1)); surface.getHolder().addCallback(this);
        Button cancel = new Button(this); cancel.setText("取消扫码，使用配对文字");cancel.setTextSize(14);cancel.setMinHeight(UiChrome.dp(this,48));UiChrome.button(cancel,theme,false); cancel.setOnClickListener(view -> finish());LinearLayout.LayoutParams buttonParams=new LinearLayout.LayoutParams(-1,-2);buttonParams.setMargins(UiChrome.dp(this,20),UiChrome.dp(this,12),UiChrome.dp(this,20),UiChrome.dp(this,16)); layout.addView(cancel,buttonParams); setContentView(layout);
    }
    @Override public void surfaceCreated(SurfaceHolder holder) { startCamera(holder); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) { release(); }
    void startCamera(SurfaceHolder holder) {
        try {
            int id = 0; Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) { Camera.getCameraInfo(i, info); if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id = i; break; } }
            Camera.getCameraInfo(id, info); camera = Camera.open(id); Camera.Parameters params = camera.getParameters();
            List<Camera.Size> sizes = params.getSupportedPreviewSizes(); Camera.Size best = sizes.get(0); for (Camera.Size size : sizes) if (size.width >= 640 && size.width <= 1280 && Math.abs(size.width * size.height - 640 * 480) < Math.abs(best.width * best.height - 640 * 480)) best = size;
            params.setPreviewSize(best.width, best.height); List<String> modes = params.getSupportedFocusModes(); if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE); camera.setParameters(params);
            int rotation = getWindowManager().getDefaultDisplay().getRotation(), degrees = rotation == Surface.ROTATION_90 ? 90 : rotation == Surface.ROTATION_180 ? 180 : rotation == Surface.ROTATION_270 ? 270 : 0;
            int display = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? (360 - (info.orientation + degrees) % 360) % 360 : (info.orientation - degrees + 360) % 360; camera.setDisplayOrientation(display); camera.setPreviewDisplay(holder);
            final int width = best.width, height = best.height;
            camera.setPreviewCallback((bytes, source) -> {
                long now = System.currentTimeMillis(); if (finished || now - lastFrame < 400 || !decoding.compareAndSet(false, true)) return; lastFrame = now; byte[] frame = Arrays.copyOf(bytes, bytes.length);
                decoder.submit(() -> {
                    try {
                        PlanarYUVLuminanceSource image = new PlanarYUVLuminanceSource(frame, width, height, 0, 0, width, height, false);
                        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class); hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE)); hints.put(DecodeHintType.TRY_HARDER, true);
                        Result result = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(image)), hints);
                        if (result.getText().startsWith("vaultbridge:") && !finished) { finished = true; runOnUiThread(() -> { setResult(RESULT_OK, new Intent().putExtra("pairing", result.getText())); finish(); }); }
                    } catch (Exception ignored) {} finally { decoding.set(false); }
                });
            }); camera.startPreview();
        } catch (Exception ex) { release(); Toast.makeText(this, "无法使用相机，请粘贴电脑配对文字。", Toast.LENGTH_LONG).show(); finish(); }
    }
    void release() { if (camera != null) { camera.setPreviewCallback(null); camera.stopPreview(); camera.release(); camera = null; } }
    @Override protected void onPause() { release(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (surface != null && surface.getHolder().getSurface().isValid() && camera == null && !finished) startCamera(surface.getHolder()); }
    @Override protected void onDestroy() { finished = true; decoder.shutdown(); release(); super.onDestroy(); }
}
