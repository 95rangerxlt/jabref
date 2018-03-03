package org.jabref.gui.nativemessaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import org.jabref.JabRefExecutorService;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.fxmisc.easybind.EasyBind;
import org.json.JSONObject;

public class StreamNativeMessagingClient implements NativeMessagingClient {

    /**
     * Holds the last message from the client that was not a response to a request from our side (i.e. a push message)
     */
    private final ObjectProperty<JSONObject> lastPushMessage = new SimpleObjectProperty<>();

    /**
     * Executor which is used to send messages. We use a single thread executor which runs sequentially in order to
     * circumvent concurrence problems (i.e. only one request is waiting for a response at a given time)
     */
    private final ExecutorService requestExecutor = Executors.newSingleThreadExecutor();

    /**
     * A list of all (active) requests.
     */
    private final List<Future<NativeMessagingResponse>> activeRequests = new ArrayList<>();

    private final InputStream in;
    private final PrintStream out;

    public StreamNativeMessagingClient(InputStream in, PrintStream out) {
        this.in = Objects.requireNonNull(in);
        this.out = Objects.requireNonNull(out);

        startPushListener();
    }

    private static int getLength(byte[] lengthBytes) {
        return (lengthBytes[3] << 24) & 0xff000000 |
                (lengthBytes[2] << 16) & 0x00ff0000 |
                (lengthBytes[1] << 8) & 0x0000ff00 |
                (lengthBytes[0]) & 0x000000ff;
    }

    private static byte[] getLengthBytes(String message) {
        int length = message.length();
        byte[] lengthBytes = new byte[4];
        lengthBytes[0] = (byte) (length & 0xFF);
        lengthBytes[1] = (byte) ((length >> 8) & 0xFF);
        lengthBytes[2] = (byte) ((length >> 16) & 0xFF);
        lengthBytes[3] = (byte) ((length >> 24) & 0xFF);
        return lengthBytes;
    }

    /**
     * Starts to listen for additional messages on the input stream, that are not coming
     */
    private void startPushListener() {
        JabRefExecutorService.INSTANCE.executeInterruptableTask(() -> {
            //noinspection InfiniteLoopStatement
            while (true) {
                if (allRequestsAreDone()) {
                    NativeMessagingResponse response = waitForResponse();
                    if (response.isSuccessful()) {
                        lastPushMessage.set(response.getJsonResponse().orElse(new JSONObject()));
                    }
                }
            }
        }, "NativeMessaging");
    }

    private boolean allRequestsAreDone() {
        activeRequests.removeIf(Future::isDone);
        return activeRequests.isEmpty();
    }

    @Override
    public void send(String message) throws IOException {
        // First write the message length (with appended zeros)
        byte[] lengthBytes = getLengthBytes(message);
        out.write(lengthBytes);

        // Write message
        out.write(message.getBytes(StandardCharsets.UTF_8));

        out.flush();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private NativeMessagingResponse waitForResponse() {
        try {
            // The first 4 bytes give the message length
            byte[] lengthBytes = new byte[4];
            in.read(lengthBytes);
            int length = getLength(lengthBytes);

            // Read content of message
            byte[] contentBytes = new byte[length];
            in.read(contentBytes);
            String content = new String(contentBytes, "UTF-8");

            return NativeMessagingResponse.fromContent(content);
        } catch (UnsupportedEncodingException e) {
            return NativeMessagingResponse.fromException("Failed to decode response", e);
        } catch (IOException e) {
            return NativeMessagingResponse.fromException("Failed to write to native messaging channel", e);
        }
    }

    @Override
    public CompletableFuture<JSONObject> sendAsync(String message) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                send(message);
                NativeMessagingResponse response = waitForResponse();
                if (response.isSuccessful()) {
                    return response.getJsonResponse().orElse(null);
                } else {
                    throw new CompletionException(response.getErrorMessage().orElse(""), response.getErrorCause().orElse(null));
                }
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, requestExecutor);
        // TODO: Add timeout: http://iteratrlearning.com/java9/2016/09/13/java9-timeouts-completablefutures.html
    }

    @Override
    public void addPushListener(Consumer<JSONObject> listener) {
        EasyBind.subscribe(lastPushMessage, listener);
    }
}
