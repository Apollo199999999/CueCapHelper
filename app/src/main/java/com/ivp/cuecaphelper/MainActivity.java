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
import android.util.Log;
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
import com.ivp.cuecaphelper.ml.FerModel;
import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.base.CameraActivity;
import com.jiangdg.ausbc.camera.bean.CameraRequest;
import com.jiangdg.ausbc.render.env.RotateType;
import com.jiangdg.ausbc.widget.AspectRatioTextureView;
import com.jiangdg.ausbc.widget.IAspectRatio;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.image.ops.TransformToGrayscaleOp;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends CameraActivity {

    //region Global Variables

    //Stores MainActivity view
    public View rootView;

    //Stores text to speech service
    public TextToSpeech tts;

    //Timer to get frames from webcam
    public Timer framesTimer = new Timer();

    //Interval in milliseconds on how often to get frames
    //Limited to twice a second to prevent tts from going crazy
    public int frameInterval = 100;

    //FaceDetector object from MLKit
    public FaceDetector faceDetector;

    //Stores emotion labels
    public List<String> emotionLabels = Arrays.asList
            ("anger", "happiness", "neutral", "sadness", "surprise-or-fear");

    //Tensorflow image processor
    public ImageProcessor tfImageProcessor =
            new ImageProcessor.Builder()
                    .add(new ResizeOp(48, 48, ResizeOp.ResizeMethod.BILINEAR))
                    .add(new TransformToGrayscaleOp())
                    .add(new NormalizeOp(0f, 255f))
                    .build();

    // Create a TensorImage object. This creates the tensor of the corresponding
    // tensor type (float32 in this case) that the TensorFlow Lite interpreter needs.
    public TensorImage tensorImage = new TensorImage(DataType.FLOAT32);

    //Stores the previous 3 detected emotions with the key being the face id,
    // used to determine whether to tts the current emotion
    public Hashtable<Integer, Queue<String>> prevEmotions = new Hashtable<Integer, Queue<String>>();
    public Hashtable<Integer, String> lastSpokenEmotions = new Hashtable<Integer, String>();

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
                        .setMinFaceSize(0.4f)
                        .enableTracking()
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

    //region UVC Camera/USB Event Handlers
    @NonNull
    @Override
    public CameraRequest getCameraRequest() {
        return new CameraRequest.Builder()
                .setPreviewWidth(485)
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

    //endregion

    //region Face detection/Tensorflow shit (god save me I'm gonna kill Caden after this)

    private class GetFrames extends TimerTask {
        //This thing's a goddamn mess
        @Override
        public void run() {
            if (isCameraOpened()) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        GetEmotion();
                    }
                });
            } else {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ImageView framesImageView = (ImageView) rootView.findViewById(R.id.framesView);
                        framesImageView.setImageBitmap(null);
                    }
                });
            }
        }
    }

    public void GetEmotion() {
        //Get bitmaps from the webcam display
        AspectRatioTextureView aspectRatioTextureView =
                (AspectRatioTextureView) rootView.findViewById(R.id.cameraView);
        ImageView framesImageView = (ImageView) rootView.findViewById(R.id.framesView);

        Bitmap frame = aspectRatioTextureView.getBitmap();

        //Put bitmap in face detector
        InputImage image = InputImage.fromBitmap(frame, 0);

        Task<List<Face>> result = faceDetector.process(image)
                .addOnSuccessListener(
                        new OnSuccessListener<List<Face>>() {
                            @Override
                            public void onSuccess(List<Face> faces) {
                                if (faces.size() > 0) {
                                    for (Face face : faces) {
                                        Rect bounds = face.getBoundingBox();

                                        //God forgive me for this cursed code I am about to do
                                        //This is supposed to wrap the dimensions of the face cropping if it exceeds the width and height of the bitmap
                                        int faceX = bounds.left;
                                        int faceY = bounds.top;
                                        int faceWidth = 0;
                                        int faceHeight = 0;

                                        if (faceX <= 0) {
                                            faceWidth = Math.min(485, bounds.right);
                                            faceX = 0;
                                        } else if (faceX > 0) {
                                            faceWidth = Math.min(bounds.width(), 485 - bounds.left);
                                        }

                                        if (faceY <= 0) {
                                            faceHeight = Math.min(360, bounds.bottom);
                                            faceY = 0;
                                        } else if (faceY > 0) {
                                            faceHeight = Math.min(bounds.height(), 360 - bounds.top);
                                        }

//                                        Log.d("face cropping", Integer.toString(faceX) + " "
//                                                + Integer.toString(faceY) + " "
//                                                + Integer.toString(faceWidth) + " "
//                                                + Integer.toString(faceHeight));

                                        //Crop the face from the frame bitmap using the rect
                                        Bitmap faceImage = Bitmap.createBitmap(frame,
                                                faceX,
                                                faceY,
                                                faceWidth,
                                                faceHeight);

                                        //Process the face image using tensorflow
                                        tensorImage.load(faceImage);
                                        tensorImage = tfImageProcessor.process(tensorImage);

                                        try {
                                            FerModel model = FerModel.newInstance(rootView.getContext());

                                            // Creates inputs for reference.
                                            TensorBuffer inputFeature0 = TensorBuffer.createFixedSize(new int[]{1, 48, 48, 1}, DataType.FLOAT32);
                                            inputFeature0.loadBuffer(tensorImage.getBuffer());

                                            // Runs model inference and gets result.
                                            FerModel.Outputs outputs = model.process(inputFeature0);
                                            TensorBuffer outputFeature0 = outputs.getOutputFeature0AsTensorBuffer();

                                            //Get the probability buffer from the output
                                            float[] emotionsProbability = outputFeature0.getFloatArray();

                                            //Get the most likely emotion
                                            //Initialize max with first element of array.
                                            float maxProbability = emotionsProbability[0];
                                            int maxIndex = 0;
                                            //Loop through the array
                                            for (int i = 0; i < emotionsProbability.length; i++) {
                                                //Compare elements of array with max
                                                if (emotionsProbability[i] > maxProbability) {
                                                    maxProbability = emotionsProbability[i];
                                                    maxIndex = i;
                                                }
                                            }

                                            //Get the associated emotion
                                            String emotion = emotionLabels.get(maxIndex);
                                            String outputEmotionLabel = String.format("%s %.2f", emotion, maxProbability * 100) + "%";

                                            // Releases model resources if no longer used.
                                            model.close();

                                            //Draw the bounding rectangle
                                            Bitmap frameMutable = frame.copy(Bitmap.Config.ARGB_8888, true);
                                            Canvas canvas = new Canvas(frameMutable);
                                            Paint borderPaint = new Paint();
                                            borderPaint.setColor(Color.rgb(50, 205, 50));
                                            borderPaint.setStrokeWidth(5);
                                            borderPaint.setStyle(Paint.Style.STROKE);
                                            canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, borderPaint);

                                            //Write the emotion
                                            Paint textPaint = new Paint();
                                            textPaint.setColor(Color.rgb(50, 205, 50));
                                            textPaint.setTextSize(18f);
                                            canvas.drawText(outputEmotionLabel, bounds.left, bounds.top - 20, textPaint);

                                            framesImageView.setImageBitmap(frameMutable);

                                            //Get the id of the detected face
                                            int faceTrackingId = face.getTrackingId();

                                            if (!prevEmotions.containsKey(faceTrackingId) || !lastSpokenEmotions.containsKey(faceTrackingId)) {
                                                //Create a new key value pair in the hashtables
                                                prevEmotions.put(faceTrackingId, new LinkedList<>());
                                                lastSpokenEmotions.put(faceTrackingId, "");
                                            }

                                            //Find the queue associated with the face ID
                                            Queue<String> prevEmotionsIndv = prevEmotions.get(faceTrackingId);

                                            //Determine whether to read the emotion using tts
                                            if (prevEmotionsIndv.size() >= 4) {
                                                prevEmotionsIndv.remove();
                                            }

                                            prevEmotionsIndv.add(emotion);
                                            prevEmotions.put(faceTrackingId, prevEmotionsIndv);

                                            //Only tts the emotion if the prev 3 emotions in the queue are equal
                                            //This is to prevent the tts from going crazy, as the ML model
                                            //usually quickly flickers through multiple emotions
                                            //when the target changes expression
                                            boolean assertPrevEmotionsEqual = true;
                                            Iterator iterator = prevEmotionsIndv.iterator();
                                            while (iterator.hasNext()) {
                                                if (!Objects.equals(emotion, iterator.next())) {
                                                    assertPrevEmotionsEqual = false;
                                                }
                                            }

                                            if (assertPrevEmotionsEqual && !Objects.equals(emotion, lastSpokenEmotions.get(faceTrackingId))) {
                                                //tts the emotion
                                                tts.speak(emotion, TextToSpeech.QUEUE_FLUSH, null, String.valueOf(java.util.UUID.randomUUID()));
                                                lastSpokenEmotions.put(faceTrackingId, emotion);
                                            }

                                        } catch (IOException e) {
                                            // die lol (just draw the unmodified frame bitmap)
                                            framesImageView.setImageBitmap(frame);
                                        }

                                    }
                                } else {
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


    //endregion
}