package dev.friendline.messenger.data;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ResultReceiver;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** A test-only, separate-UID image viewer used to verify FileProvider URI grants. */
public final class TestAttachmentViewerActivity extends Activity {
    public static final String EXTRA_RESULT_RECEIVER =
            "dev.friendline.messenger.test.VIEWER_RESULT_RECEIVER";
    public static final String EXTRA_BYTES = "dev.friendline.messenger.test.VIEWER_BYTES";
    public static final String EXTRA_ERROR = "dev.friendline.messenger.test.VIEWER_ERROR";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ResultReceiver receiver = resultReceiver();
        int resultCode = RESULT_CANCELED;
        Bundle resultData = new Bundle();
        try {
            Intent intent = getIntent();
            if (!Intent.ACTION_VIEW.equals(intent.getAction())) {
                throw new IllegalArgumentException("Unexpected viewer action");
            }
            String mimeType = intent.getType();
            if (mimeType == null || !"image/jpeg".equalsIgnoreCase(mimeType)) {
                throw new IllegalArgumentException("Unexpected viewer MIME type");
            }
            Uri uri = intent.getData();
            if (uri == null) {
                throw new IllegalArgumentException("The viewer did not receive a content URI");
            }
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    throw new IllegalStateException("The viewer could not open the granted content URI");
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                resultData.putByteArray(EXTRA_BYTES, output.toByteArray());
                resultCode = RESULT_OK;
            }
        } catch (Exception failure) {
            resultData.putString(EXTRA_ERROR, failure.getClass().getSimpleName());
        }

        if (receiver != null) {
            receiver.send(resultCode, resultData);
        }
        finish();
    }

    @SuppressWarnings("deprecation")
    private ResultReceiver resultReceiver() {
        Intent intent = getIntent();
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver.class);
        }
        return intent.getParcelableExtra(EXTRA_RESULT_RECEIVER);
    }
}
