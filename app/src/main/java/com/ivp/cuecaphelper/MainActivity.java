package com.ivp.cuecaphelper;

import android.Manifest;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.view.Surface;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.hjq.permissions.XXPermissions;
import com.serenegiant.common.BaseActivity;
import com.serenegiant.widget.AspectRatioTextureView;
import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usbcameracommon.UVCCameraHandler;
import com.serenegiant.widget.CameraViewInterface;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BaseActivity  {
    private CameraViewInterface mUVCCameraView;
    private USBMonitor mUSBMonitor;
    /**
     * set true if you want to record movie using MediaSurfaceEncoder
     * (writing frame data into Surface camera from MediaCodec
     *  by almost same way as USBCameratest2)
     * set false if you want to record movie using MediaVideoEncoder
     */
    private static final boolean USE_SURFACE_ENCODER = false;
    /**
     * preview resolution(width)
     * if your camera does not support specific resolution and mode,
     * UVCCamera#setPreviewSize(int, int, int) throw exception
     */
    private static final int PREVIEW_WIDTH = 960;
    /**
     * preview resolution(height)
     * if your camera does not support specific resolution and mode,
     * UVCCamera#setPreviewSize(int, int, int) throw exception
     */
    private static final int PREVIEW_HEIGHT = 720;
    /**
     * preview mode
     * if your camera does not support specific resolution and mode,
     * VCCamera#setPreviewSize(int, int, int) throw exception
     * 0:YUYV, other:MJPEG
     */
    private static final int PREVIEW_MODE = 0; // YUV
    private UVCCameraHandler mCameraHandler;
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        //Request perms
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        List<String> needPermissions = new ArrayList<>();
        needPermissions.add(Manifest.permission.CAMERA);
        needPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        needPermissions.add(Manifest.permission.RECORD_AUDIO);

        XXPermissions.with(this)
                .permission(needPermissions)
                .request((permissions, allGranted) -> {
                    if (!allGranted) {
                        return;
                    }
                });

        final View view = findViewById(R.id.camera_view);
        mUVCCameraView = (CameraViewInterface) view;
        mUVCCameraView.setAspectRatio(PREVIEW_WIDTH / (double)PREVIEW_HEIGHT);
        mUSBMonitor = new USBMonitor(this, mOnDeviceConnectListener);
        mCameraHandler = UVCCameraHandler.createHandler(this, mUVCCameraView,
                USE_SURFACE_ENCODER ? 0 : 1, PREVIEW_WIDTH, PREVIEW_HEIGHT, PREVIEW_MODE);
    }

    private final USBMonitor.OnDeviceConnectListener mOnDeviceConnectListener = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            mUSBMonitor.requestPermission(device);
            Toast.makeText(MainActivity.this, "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onConnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock, final boolean createNew) {
            if (mCameraHandler != null) {
                mCameraHandler.open(ctrlBlock);
                startPreview();
            }
        }

        @Override
        public void onDisconnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock) {

        }
        @Override
        public void onDettach(final UsbDevice device) {
            Toast.makeText(MainActivity.this, "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onCancel(final UsbDevice device) {
        }
    };

    private void startPreview() {
        if (mCameraHandler != null) {
            final SurfaceTexture st = mUVCCameraView.getSurfaceTexture();
            mCameraHandler.startPreview(new Surface(st));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mUSBMonitor.register();
    }

    @Override
    protected void onStop() {
        mUSBMonitor.unregister();
        super.onStop();
    }

    @Override
    public void onDestroy() {
        if (mUSBMonitor != null) {
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }
        super.onDestroy();
    }


}