package wellatleastitried.mediagarrdclient.service;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SemVer(long major, long minor, long patch, List<String> prerelease) implements Comparable<SemVer> {

    private static final String IDENTIFIERS = "[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*";
    private static final Pattern PATTERN = Pattern.compile(
        "v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-(" + IDENTIFIERS + "))?(?:\\+" + IDENTIFIERS + ")?");

    public static Optional<SemVer> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = PATTERN.matcher(text.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            List<String> prerelease = matcher.group(4) == null ? List.of() : Arrays.asList(matcher.group(4).split("\\."));
            return Optional.of(new SemVer(
                Long.parseLong(matcher.group(1)),
                Long.parseLong(matcher.group(2)),
                Long.parseLong(matcher.group(3)),
                prerelease));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public int compareTo(SemVer other) {
        int result = Long.compare(major, other.major);
        if (result == 0) {
            result = Long.compare(minor, other.minor);
        }
        if (result == 0) {
            result = Long.compare(patch, other.patch);
        }
        return result != 0 ? result : comparePrerelease(prerelease, other.prerelease);
    }

    @Override
    public String toString() {
        String core = major + "." + minor + "." + patch;
        return prerelease.isEmpty() ? core : core + "-" + String.join(".", prerelease);
    }

    private static int comparePrerelease(List<String> left, List<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return Boolean.compare(left.isEmpty(), right.isEmpty());
        }
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            int result = compareIdentifier(left.get(i), right.get(i));
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private static int compareIdentifier(String left, String right) {
        boolean leftNumeric = left.chars().allMatch(Character::isDigit);
        boolean rightNumeric = right.chars().allMatch(Character::isDigit);
        if (leftNumeric && rightNumeric) {
            return new BigInteger(left).compareTo(new BigInteger(right));
        }
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? -1 : 1;
        }
        return left.compareTo(right);
    }
}
