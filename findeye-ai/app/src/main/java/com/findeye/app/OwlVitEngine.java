package com.findeye.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

public final class OwlVitEngine implements AutoCloseable {
    private static final int MODEL_SIZE = 768;
    private static final float[] MEAN = {0.48145466f, 0.4578275f, 0.40821073f};
    private static final float[] STD = {0.26862954f, 0.26130258f, 0.27577711f};

    private final OrtEnvironment env;
    private final OrtSession session;
    private final ClipTokenizer tokenizer;

    private final float[] imageInput = new float[3 * MODEL_SIZE * MODEL_SIZE];
    private final int[] pixels = new int[MODEL_SIZE * MODEL_SIZE];

    private int[] currentIds = new int[ClipTokenizer.CONTEXT_LENGTH];
    private int[] currentMask = new int[ClipTokenizer.CONTEXT_LENGTH];

    public OwlVitEngine(Context context, String modelPath) throws Exception {
        tokenizer = new ClipTokenizer(context);
        env = OrtEnvironment.getEnvironment();

        OrtSession.SessionOptions options = new OrtSession.SessionOptions();

        try {
            Method addNnapi = options.getClass().getMethod("addNnapi");
            addNnapi.invoke(options);
        } catch (Throwable ignored) {
            // CPU fallback is automatic.
        }

        session = env.createSession(modelPath, options);
    }

    public synchronized void setQuery(String query) {
        String q = query == null ? "" : query.trim();
        boolean containsLatin = q.matches(".*[A-Za-z].*");
        if (containsLatin && !q.toLowerCase().startsWith("a photo of")) {
            q = "a photo of " + q;
        }

        ClipTokenizer.TokenResult result = tokenizer.tokenize(q);
        currentIds = result.ids;
        currentMask = result.attentionMask;
    }

    public synchronized Detection detect(
            byte[] nv21,
            int width,
            int height,
            int rotationDegrees
    ) throws Exception {
        Bitmap source = nv21ToBitmap(nv21, width, height);
        if (source == null) return null;

        Bitmap upright = source;
        if (rotationDegrees != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotationDegrees);
            upright = Bitmap.createBitmap(
                    source, 0, 0, source.getWidth(), source.getHeight(), matrix, true
            );
        }

        Bitmap resized = Bitmap.createScaledBitmap(upright, MODEL_SIZE, MODEL_SIZE, true);
        resized.getPixels(pixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE);

        if (resized != upright) resized.recycle();
        if (upright != source) upright.recycle();
        source.recycle();

        final int plane = MODEL_SIZE * MODEL_SIZE;
        for (int i = 0; i < plane; i++) {
            int c = pixels[i];
            float r = ((c >> 16) & 0xff) / 255.0f;
            float g = ((c >> 8) & 0xff) / 255.0f;
            float b = (c & 0xff) / 255.0f;

            imageInput[i] = (r - MEAN[0]) / STD[0];
            imageInput[plane + i] = (g - MEAN[1]) / STD[1];
            imageInput[2 * plane + i] = (b - MEAN[2]) / STD[2];
        }

        try (
                OnnxTensor imageTensor = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.wrap(imageInput),
                        new long[]{1, 3, MODEL_SIZE, MODEL_SIZE}
                );
                OnnxTensor idsTensor = OnnxTensor.createTensor(
                        env,
                        IntBuffer.wrap(currentIds),
                        new long[]{1, ClipTokenizer.CONTEXT_LENGTH}
                );
                OnnxTensor maskTensor = OnnxTensor.createTensor(
                        env,
                        IntBuffer.wrap(currentMask),
                        new long[]{1, ClipTokenizer.CONTEXT_LENGTH}
                )
        ) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("pixel_values", imageTensor);
            inputs.put("input_ids", idsTensor);
            inputs.put("attention_mask", maskTensor);

            try (OrtSession.Result result = session.run(inputs)) {
                Optional<OnnxValue> boxValue = result.get("boxes");
                Optional<OnnxValue> scoreValue = result.get("scores");
                if (!boxValue.isPresent() || !scoreValue.isPresent()) return null;

                float[][][] boxes = (float[][][]) boxValue.get().getValue();
                float[][] scores = (float[][]) scoreValue.get().getValue();

                int best = -1;
                float bestScore = 0f;
                for (int i = 0; i < scores[0].length; i++) {
                    float score = scores[0][i];
                    if (score > bestScore) {
                        bestScore = score;
                        best = i;
                    }
                }

                if (best < 0) return null;

                float[] b = boxes[0][best];
                return new Detection(
                        clamp(b[0], 0, MODEL_SIZE),
                        clamp(b[1], 0, MODEL_SIZE),
                        clamp(b[2], 0, MODEL_SIZE),
                        clamp(b[3], 0, MODEL_SIZE),
                        bestScore
                );
            }
        }
    }

    private static Bitmap nv21ToBitmap(byte[] data, int width, int height) {
        try {
            YuvImage image = new YuvImage(data, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            image.compressToJpeg(new Rect(0, 0, width, height), 88, out);
            byte[] jpeg = out.toByteArray();
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        } catch (Throwable t) {
            return null;
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public void close() throws Exception {
        session.close();
    }

    public static final class Detection {
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;
        public final float score;

        public Detection(float left, float top, float right, float bottom, float score) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.score = score;
        }
    }
}
