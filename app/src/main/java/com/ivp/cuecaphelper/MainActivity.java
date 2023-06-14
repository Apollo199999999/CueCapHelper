package com.ivp.cuecaphelper;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.speech.tts.TextToSpeech;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.hjq.permissions.XXPermissions;
import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.base.CameraActivity;
import com.jiangdg.ausbc.camera.bean.CameraRequest;
import com.jiangdg.ausbc.render.env.RotateType;
import com.jiangdg.ausbc.widget.AspectRatioTextureView;
import com.jiangdg.ausbc.widget.IAspectRatio;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

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

    //FaceDetector object from MLKit
    FaceDetector faceDetector;

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

        //Init FaceDetector
        FaceDetectorOptions faceDetectorOptions =
                new FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                        .build();
        faceDetector = FaceDetection.getClient(faceDetectorOptions);

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
                .setPreviewWidth(490)
                .setPreviewHeight(360)
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

    //endregion%%%

    //region Face detection/Tensorflow shit (god save me I'm gonna kill Caden after this)

    private class GetFrames extends TimerTask {
        //This thing's a goddamn mess
        @Override
        public void run() {
            if (isCameraOpened()) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        //Get bitmaps from the webcam display
                        AspectRatioTextureView aspectRatioTextureView =
                                (AspectRatioTextureView) rootView.findViewById(R.id.cameraView);
                        ImageView framesImageView = (ImageView) rootView.findViewById(R.id.framesView);

                        Bitmap frame = aspectRatioTextureView.getBitmap();

                        //Put bitmap in face detector
                        InputImage image = InputImage.fromBitmap(frame, 0);

                        Task<List<Face>> result =
                                faceDetector.process(image)
                                        .addOnSuccessListener(
                                                new OnSuccessListener<List<Face>>() {
                                                    @Override
                                                    public void onSuccess(List<Face> faces) {
                                                        if (faces.size() > 0) {
                                                            for (Face face : faces) {
                                                                Rect bounds = face.getBoundingBox();

                                                                //Draw the bounding rectangle
                                                                Bitmap frameMutable = frame.copy(Bitmap.Config.ARGB_8888, true);
                                                                Canvas canvas = new Canvas(frameMutable);
                                                                Paint myPaint = new Paint();
                                                                myPaint.setColor(Color.rgb(0, 0, 0));
                                                                myPaint.setStrokeWidth(10);
                                                                myPaint.setStyle(Paint.Style.STROKE);
                                                                canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, myPaint);

                                                                framesImageView.setImageBitmap(frameMutable);
                                                            }
                                                        }
                                                        else {
                                                            //Just set the unmodified frame bitmap
                                                            framesImageView.setImageBitmap(frame);
                                                        }
                                                    }
                                                })
                                        .addOnFailureListener(
                                                new OnFailureListener() {
                                                    @Override
                                                    public void onFailure(@NonNull Exception e) {
                                                        //Just set the unmodified frame bitmap
                                                        framesImageView.setImageBitmap(frame);
                                                    }
                                                });
                    }
                });

            }
        }
    }

    //endregion
}