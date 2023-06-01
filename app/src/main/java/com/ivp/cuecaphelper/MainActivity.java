package com.ivp.cuecaphelper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.SurfaceHolder;

import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.hjq.permissions.XXPermissions;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.widget.AspectRatioSurfaceView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    //Global TTS service
    public TextToSpeech tts;

    //Camera Helpers
    private ICameraHelper mCameraHelper;
    private AspectRatioSurfaceView mCameraViewMain;
    private ICameraHelper.StateCallback mStateListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //Request perms
        List<String> needPermissions = new ArrayList<>();
        needPermissions.add(android.Manifest.permission.CAMERA);

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

        //Init Camera stuff
        mStateListener = new StateListenerClass();
        mCameraViewMain = (AspectRatioSurfaceView) findViewById(R.id.surfaceView);
        mCameraViewMain.getHolder().addCallback(new SurfaceHolderCallback());
        mCameraHelper = new CameraHelper();
        mCameraHelper.setStateCallback(mStateListener);
    }


    private class StateListenerClass implements ICameraHelper.StateCallback {
        @Override
        public void onAttach(UsbDevice device) {
            tts.speak("Webcam attached.", TextToSpeech.QUEUE_ADD, null, String.valueOf(java.util.UUID.randomUUID()));
            mCameraHelper.selectDevice(device);
        }

        @Override
        public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
            mCameraHelper.openCamera(new Size(UVCCamera.FRAME_FORMAT_YUYV, 640, 480, 25, new ArrayList<>(25)));
        }

        @Override
        public void onCameraOpen(UsbDevice device) {
            mCameraHelper.startPreview();
            mCameraViewMain.setAspectRatio(640, 480);
            mCameraHelper.setPreviewSize(new Size(UVCCamera.FRAME_FORMAT_YUYV, 640, 480, 25, new ArrayList<>(25)));
            mCameraHelper.addSurface(mCameraViewMain.getHolder().getSurface(), false);
        }

        @Override
        public void onCameraClose(UsbDevice device) {
            if (mCameraHelper != null) {
                mCameraHelper.removeSurface(mCameraViewMain.getHolder().getSurface());
            }
        }

        @Override
        public void onDeviceClose(UsbDevice device) {

        }

        @Override
        public void onDetach(UsbDevice device) {
            tts.speak("Webcam detached.", TextToSpeech.QUEUE_ADD, null, String.valueOf(java.util.UUID.randomUUID()));
        }

        @Override
        public void onCancel(UsbDevice device) {

        }
    }

    ;

    private class SurfaceHolderCallback implements SurfaceHolder.Callback {

        @Override
        public void surfaceCreated(@NonNull SurfaceHolder surfaceHolder) {
            if (mCameraHelper != null) {
                mCameraHelper.addSurface(surfaceHolder.getSurface(), false);
            }
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder surfaceHolder, int i, int i1, int i2) {
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder surfaceHolder) {
            if (mCameraHelper != null) {
                mCameraHelper.removeSurface(surfaceHolder.getSurface());
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mCameraHelper.release();
    }

}