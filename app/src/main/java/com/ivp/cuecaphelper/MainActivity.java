package com.ivp.cuecaphelper;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;


import com.google.android.material.snackbar.Snackbar;
import com.hjq.permissions.XXPermissions;
import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.base.CameraActivity;
import com.jiangdg.ausbc.callback.ICaptureCallBack;
import com.jiangdg.ausbc.camera.bean.CameraRequest;
import com.jiangdg.ausbc.camera.bean.PreviewSize;
import com.jiangdg.ausbc.render.env.RotateType;
import com.jiangdg.ausbc.widget.AspectRatioSurfaceView;
import com.jiangdg.ausbc.widget.IAspectRatio;


import java.util.ArrayList;
import java.util.List;

public class MainActivity extends CameraActivity implements View.OnClickListener {

    public View rootView;

    @Nullable
    @Override
    protected View getRootView(@NonNull LayoutInflater layoutInflater) {
        rootView = getLayoutInflater().inflate(R.layout.activity_main, null);

        //Request perms
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


        Button button = rootView.findViewById(R.id.takePhotoBtn);
        button.setOnClickListener(this);

        return rootView;
    }

    @NonNull
    @Override
    public CameraRequest getCameraRequest() {
        return new CameraRequest.Builder()
                .setPreviewWidth(1280)
                .setPreviewHeight(720)
                .setRenderMode(CameraRequest.RenderMode.NORMAL)
                .setDefaultRotateType(RotateType.ANGLE_0)
                .setAudioSource(CameraRequest.AudioSource.SOURCE_AUTO)
                .setAspectRatioShow(true)
                .setCaptureRawImage(true)
                .setRawPreviewData(true)
                .create();
    }

    @Nullable
    @Override
    protected IAspectRatio getCameraView() {
        return (AspectRatioSurfaceView) rootView.findViewById(R.id.cameraView);
    }

    @Nullable
    @Override
    protected ViewGroup getCameraViewContainer() {
        return rootView.findViewById(R.id.cameraViewContainer);
    }

    @Override
    public void onCameraState(@NonNull MultiCameraClient.ICamera iCamera, @NonNull State state, @Nullable String s) {
        switch (state) {
            case OPENED:
                Snackbar.make(rootView, "open", Snackbar.LENGTH_SHORT).show();
            case CLOSED:
                Snackbar.make(rootView, "close", Snackbar.LENGTH_SHORT).show();
            case ERROR:
                Snackbar.make(rootView, s, Snackbar.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onClick(View view) {
        captureImage(new ICaptureCallBack() {
            @Override
            public void onBegin() {

            }

            @Override
            public void onError(@Nullable String s) {
                Snackbar.make(rootView, s, Snackbar.LENGTH_SHORT).show();
            }

            @Override
            public void onComplete(@Nullable String s) {

            }
        }, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() + "/image.jpg");
    }
}