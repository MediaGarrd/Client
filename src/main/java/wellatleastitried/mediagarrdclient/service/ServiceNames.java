package wellatleastitried.mediagarrdclient.service;

import java.util.regex.Pattern;

public final class ServiceNames {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    private ServiceNames() {
    }

    public static String require(String name) {
        if (name == null || !VALID.matcher(name).matches()) {
            throw new InvalidRequestException("Service names use letters, digits, '.', '_' and '-', start with a letter or digit and have at most 64 characters");
        }
        return name;
    }
}
