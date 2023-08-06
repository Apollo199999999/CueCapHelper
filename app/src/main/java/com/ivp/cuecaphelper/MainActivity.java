package com.ivp.cuecaphelper;

import android.Manifest;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

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

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.model.Model;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

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
import java.util.concurrent.ExecutionException;

import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

public class MainActivity extends AppCompatActivity {

    //region Global Variables

    //Stores text to speech service
    public TextToSpeech tts;

    //Timer to get frames from webcam
    public Timer framesTimer = new Timer();

    //Interval in milliseconds on how often to get frames
    public int frameInterval = 300;

    //Camera Preview shit
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;

    //FaceDetector object from MLKit
    public FaceDetector faceDetector;

    //Stores emotion labels
    public List<String> emotionLabels = Arrays.asList
            ("anger", "happiness", "neutral", "sadness", "surprise-or-fear");

    //Tensorflow image processor
    public ImageProcessor tfImageProcessor =
            new ImageProcessor.Builder()
                    .add(new ResizeOp(48, 48, ResizeOp.ResizeMethod.BILINEAR))
                    .add(new ResizeOp(75, 75, ResizeOp.ResizeMethod.BILINEAR))
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
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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

        //Get camera preview
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                PreviewView previewView = (PreviewView) findViewById(R.id.cameraView);
                previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindPreview(cameraProvider, previewView);
            } catch (ExecutionException | InterruptedException e) {
                // No errors need to be handled for this Future.
                // This should never be reached.
            }
        }, ContextCompat.getMainExecutor(this));

        //Start frames timer
        framesTimer.scheduleAtFixedRate(new GetFrames(), 0, frameInterval);
    }

    @Override
    public void onDestroy() {
        //Kill the framesTimer if running
        framesTimer.cancel();
        framesTimer.purge();

        super.onDestroy();
    }

    //endregion

    //region Face detection/Tensorflow shit (god save me I'm gonna kill Caden after this)

    //Function to get camera preview
    public void bindPreview(@NonNull ProcessCameraProvider cameraProvider, PreviewView previewView) {
        Preview preview = new Preview.Builder()
                .build();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        Camera camera = cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, preview);
    }

    private class GetFrames extends TimerTask {
        //This thing's a goddamn mess
        @Override
        public void run() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    //Get bitmaps from the webcam display
                    PreviewView previewView = (PreviewView) findViewById(R.id.cameraView);
                    Bitmap frame = previewView.getBitmap();

                    if (frame != null) {
                        GetEmotion(frame);
                    }
                }
            });
        }
    }

    public void GetEmotion(Bitmap frame) {
        //Get imageview to put processed image
        ImageView framesImageView = (ImageView) findViewById(R.id.framesView);

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
                                            faceWidth = Math.min(480, bounds.right);
                                            faceX = 0;
                                        } else if (faceX > 0) {
                                            faceWidth = Math.min(bounds.width(), 480 - bounds.left);
                                        }

                                        if (faceY <= 0) {
                                            faceHeight = Math.min(360, bounds.bottom);
                                            faceY = 0;
                                        } else if (faceY > 0) {
                                            faceHeight = Math.min(bounds.height(), 360 - bounds.top);
                                        }

                                        //Set the faceWidth and faceHeight to be at least 1
                                        faceWidth = Math.max(1, faceWidth);
                                        faceHeight = Math.max(1, faceHeight);

                                        //Set the faceX to be no more than width, faceY to be no more than height
                                        faceX = Math.min(faceX, 479);
                                        faceY = Math.min(faceY, 359);

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

                                        faceImage = toGrayScale(faceImage);

                                        //Process the face image using tensorflow
                                        tensorImage.load(faceImage);
                                        tensorImage = tfImageProcessor.process(tensorImage);

                                        try {
                                            // Initialize interpreter
                                            Model.Options options;
                                            options = new Model.Options.Builder().setNumThreads(4).build();
                                            FerModel model = FerModel.newInstance(getApplicationContext(), options);

                                            // Creates inputs for reference.
                                            TensorBuffer inputFeature0 = TensorBuffer.createFixedSize(new int[]{1, 75, 75, 3}, DataType.FLOAT32);
                                            inputFeature0.loadBuffer(tensorImage.getBuffer());

                                            // Runs model inference and gets result.
                                            FerModel.Outputs outputs = model.process(inputFeature0);
                                            TensorBuffer outputFeature0 = outputs.getOutputFeature0AsTensorBuffer();

                                            //Get the probability buffer from the output
                                            float[] emotionsProbability = outputFeature0.getFloatArray();

                                            // Releases model resources if no longer used.
                                            model.close();

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
                                            if (prevEmotionsIndv.size() >= 2) {
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

                                        } catch (Exception e) {
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


    public Bitmap toGrayScale(Bitmap bmpOriginal) {

        int width, height;
        height = bmpOriginal.getHeight();
        width = bmpOriginal.getWidth();

        Bitmap bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmpGrayscale);
        Paint paint = new Paint();
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0);
        ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);
        paint.setColorFilter(f);
        c.drawBitmap(bmpOriginal, 0, 0, paint);
        bmpOriginal.recycle();
        return bmpGrayscale;
    }
    //endregion
}