package com.ivp.cuecaphelper;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Environment;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;


import com.google.android.material.snackbar.Snackbar;
import com.hjq.permissions.XXPermissions;
import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.base.CameraActivity;
import com.jiangdg.ausbc.callback.ICaptureCallBack;
import com.jiangdg.ausbc.camera.CameraUVC;
import com.jiangdg.ausbc.camera.bean.CameraRequest;
import com.jiangdg.ausbc.camera.bean.PreviewSize;
import com.jiangdg.ausbc.render.env.RotateType;
import com.jiangdg.ausbc.widget.AspectRatioSurfaceView;
import com.jiangdg.ausbc.widget.AspectRatioTextureView;
import com.jiangdg.ausbc.widget.IAspectRatio;


import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Logger;

public class MainActivity extends CameraActivity {

    //region Global Variables

    //Stores MainActivity view
    public View rootView;

    //Stores text to speech service
    public TextToSpeech tts;

    //Timer to get frames from webcam
    private Timer framesTimer = new Timer();

    //Interval in milliseconds on how often to get frames
    private int frameInterval = 100;

    //endregion

    //region App Initialization
    @Nullable
    @Override
    protected View getRootView(@NonNull LayoutInflater layoutInflater) {
        rootView = getLayoutInflater().inflate(R.layout.activity_main, null);

        //Request perms
        List<String> needPermissions = new ArrayList<>();
        needPermissions.add(Manifest.permission.CAMERA);
        needPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);

        XXPermissions.with(this)
                .permission(needPermissions)
                .request((permissions, allGranted) -> {
                    if (!allGranted) {
                        return;
                    }
                });

        //Init TTS
        tts = new TextToSpeech(getApplicationContext(), i -> {
            if (i == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.ENGLISH);
                tts.speak("Text to speech service initialised.", TextToSpeech.QUEUE_FLUSH, null, null);
            }
        });

        //Bind USB events
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        registerReceiver(mUsbAttachReceiver, filter);
        filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(mUsbDetachReceiver, filter);

        //Start frames timer
        framesTimer.scheduleAtFixedRate(new GetFrames(), 0, frameInterval);

        return rootView;
    }

    //endregion

    //region UVC Camera Event Handlers
    @NonNull
    @Override
    public CameraRequest getCameraRequest() {
        return new CameraRequest.Builder()
                .setPreviewWidth(640)
                .setPreviewHeight(480)
                .setRenderMode(CameraRequest.RenderMode.NORMAL)
                .setDefaultRotateType(RotateType.ANGLE_0)
                .setAudioSource(CameraRequest.AudioSource.NONE)
                .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_YUYV)
                .setAspectRatioShow(true)
                .setCaptureRawImage(true)
                .setRawPreviewData(true)
                .create();
    }

    @Nullable
    @Override
    protected IAspectRatio getCameraView() {
        return (AspectRatioTextureView) rootView.findViewById(R.id.cameraView);
    }

    @Nullable
    @Override
    protected ViewGroup getCameraViewContainer() {
        return rootView.findViewById(R.id.cameraViewContainer);
    }

    @Override
    public void onCameraState(@NonNull MultiCameraClient.ICamera iCamera, @NonNull State state, @Nullable String s) {
    }

    BroadcastReceiver mUsbAttachReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                tts.speak("Webcam attached.", TextToSpeech.QUEUE_FLUSH, null, String.valueOf(java.util.UUID.randomUUID()));
            }
        }
    };

    BroadcastReceiver mUsbDetachReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    tts.speak("Webcam detached.", TextToSpeech.QUEUE_FLUSH, null, String.valueOf(java.util.UUID.randomUUID()));
                }
            }
        }
    };

    @Override
    public void onDestroy() {
        unregisterReceiver(mUsbDetachReceiver);
        unregisterReceiver(mUsbAttachReceiver);

        //Kill the framesTimer if running
        framesTimer.cancel();
        framesTimer.purge();

        super.onDestroy();
    }

    //endregion

    //region OpenCV shit
    private class GetFrames extends TimerTask {
        //This thing's a goddamn mess
        @Override
        public void run() {
            if (isCameraOpened()) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        //Get bitmaps from the webcam and display it in the imageView
                        AspectRatioTextureView aspectRatioTextureView =
                                (AspectRatioTextureView) rootView.findViewById(R.id.cameraView);
                        Bitmap frame = aspectRatioTextureView.getBitmap();

                        ImageView framesImageView = (ImageView) rootView.findViewById(R.id.framesView);
                        framesImageView.setImageBitmap(frame);
                    }
                });

            }
        }
    }
    //endregion
}