package com.ivp.cuecaphelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import com.google.android.material.snackbar.Snackbar;
import com.jiangdg.ausbc.camera.CameraUVC;
import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.base.MultiCameraActivity;
import com.jiangdg.ausbc.callback.ICameraStateCallBack;
import com.jiangdg.ausbc.widget.AspectRatioTextureView;

public class MainActivity extends MultiCameraActivity implements ICameraStateCallBack {
    public View rootView;
    @Nullable
    @Override
    protected View getRootView(@NonNull LayoutInflater layoutInflater) {
        rootView = getLayoutInflater().inflate(R.layout.activity_main, null);
        return rootView;
    }

    @NonNull
    @Override
    public MultiCameraClient.ICamera generateCamera(@NonNull Context context, @NonNull UsbDevice usbDevice) {
        return null;
    }

    @Override
    protected void onCameraAttached(@NonNull MultiCameraClient.ICamera iCamera) {
        Snackbar.make(rootView, "hello", Snackbar.LENGTH_SHORT).show();
    }

    @Override
    protected void onCameraConnected(@NonNull MultiCameraClient.ICamera iCamera) {
        Snackbar.make(rootView, "hello", Snackbar.LENGTH_SHORT).show();
        iCamera.openCamera((AspectRatioTextureView)findViewById(R.id.camera_view), iCamera.getCameraRequest());
        iCamera.setCameraStateCallBack(this);
    }

    @Override
    protected void onCameraDetached(@NonNull MultiCameraClient.ICamera iCamera) {

    }

    @Override
    protected void onCameraDisConnected(@NonNull MultiCameraClient.ICamera iCamera) {

    }

    @Override
    public void onCameraState(@NonNull MultiCameraClient.ICamera iCamera, @NonNull State state, @Nullable String s) {

    }
}