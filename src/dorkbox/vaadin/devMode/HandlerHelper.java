//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package dorkbox.vaadin.devMode;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;

/**
 * THIS IS COPIED DIRECTLY FROM VAADIN 14.6.8 (flow 2.4.6)
 *
 * CHANGES FROM DEFAULT ARE MANAGED AS DIFFERENT REVISIONS.
 *
 * The initial commit is exactly as-is from vaadin.
 *
 * This file is NOT extensible/configurable AT-ALL, so this is required...
 */
public class HandlerHelper implements Serializable {
//    static final SystemMessages DEFAULT_SYSTEM_MESSAGES = new SystemMessages();
    static final String UNSAFE_PATH_ERROR_MESSAGE_PATTERN = "Blocked attempt to access file: {}";
    private static final Pattern PARENT_DIRECTORY_REGEX = Pattern.compile("(/|\\\\)\\.\\.(/|\\\\)?", 2);

    private HandlerHelper() {
    }

    public static boolean isRequestType(VaadinRequest request, HandlerHelper.RequestType requestType) {
        return requestType.getIdentifier().equals(request.getParameter("v-r"));
    }

    public static Locale findLocale(VaadinSession session, VaadinRequest request) {
        if (session == null) {
            session = VaadinSession.getCurrent();
        }

        Locale locale;
        if (session != null) {
            locale = session.getLocale();
            if (locale != null) {
                return locale;
            }
        }

        if (request == null) {
            request = VaadinService.getCurrentRequest();
        }

        if (request != null) {
            locale = request.getLocale();
            if (locale != null) {
                return locale;
            }
        }

        return Locale.getDefault();
    }

    public static void setResponseNoCacheHeaders(BiConsumer<String, String> headerSetter, BiConsumer<String, Long> longHeaderSetter) {
        headerSetter.accept("Cache-Control", "no-cache, no-store");
        headerSetter.accept("Pragma", "no-cache");
        longHeaderSetter.accept("Expires", 0L);
    }

    public static String getCancelingRelativePath(String pathToCancel) {
        StringBuilder sb = new StringBuilder(".");

        for(int i = 1; i < pathToCancel.length(); ++i) {
            if (pathToCancel.charAt(i) == '/') {
                sb.append("/..");
            }
        }

        return sb.toString();
    }

    static boolean isPathUnsafe(String path) {
        try {
            path = URLDecoder.decode(path, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException var2) {
            throw new RuntimeException("An error occurred during decoding URL.", var2);
        }

        return PARENT_DIRECTORY_REGEX.matcher(path).find();
    }

    public static enum RequestType {
        UIDL("uidl"),
        HEARTBEAT("heartbeat"),
        PUSH("push");

        private String identifier;

        private RequestType(String identifier) {
            this.identifier = identifier;
        }

        public String getIdentifier() {
            return this.identifier;
        }
    }
}
