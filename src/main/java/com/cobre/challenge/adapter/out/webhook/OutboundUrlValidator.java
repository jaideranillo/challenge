package com.cobre.challenge.adapter.out.webhook;

import com.cobre.challenge.adapter.out.webhook.config.EgressProperties;
import com.cobre.challenge.adapter.out.webhook.dto.EgressVerdict;
import java.io.UncheckedIOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Decides whether this service may call one target, resolving DNS on every call (ADR-002 A01).
 * Validation happens at send time on every attempt, never once at subscription creation: a host
 * that resolved publicly yesterday can resolve to {@code 127.0.0.1} today (DNS rebinding), and
 * per-attempt resolution is the whole defense against it.
 *
 * <p>{@code challenge.egress.allowed-hosts} exempts a host from the private/reserved-range check;
 * under the {@code local} profile only, the same list entry also exempts the host from the HTTPS
 * requirement. Neither condition alone waives anything, and every other profile keeps both rules
 * absolute.
 */
@Component
@EnableConfigurationProperties(EgressProperties.class)
public class OutboundUrlValidator {

    private static final Function<String, InetAddress[]> DEFAULT_RESOLVER = host -> {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new UncheckedIOException(e);
        }
    };

    private final Function<String, InetAddress[]> resolver;
    private final Set<String> allowedHosts;
    private final boolean localProfileActive;

    public OutboundUrlValidator(EgressProperties properties, Environment environment) {
        this(properties, environment.acceptsProfiles(Profiles.of("local")), DEFAULT_RESOLVER);
    }

    OutboundUrlValidator(
            EgressProperties properties,
            boolean localProfileActive,
            Function<String, InetAddress[]> resolver) {
        this.resolver = resolver;
        this.allowedHosts = properties.allowedHosts().stream()
                .map(host -> host.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.localProfileActive = localProfileActive;
    }

    public EgressVerdict validate(String targetUrl) {
        URI uri;
        try {
            uri = new URI(targetUrl);
        } catch (URISyntaxException e) {
            return EgressVerdict.policyRejected("egress: malformed target URL");
        }

        String host = uri.getHost();
        if (host == null) {
            return EgressVerdict.policyRejected("egress: target has no host");
        }
        boolean waived = localProfileActive && allowedHosts.contains(host.toLowerCase(Locale.ROOT));

        String scheme = uri.getScheme();
        boolean isHttps = scheme != null && scheme.equalsIgnoreCase("https");
        if (!isHttps && !waived) {
            return EgressVerdict.policyRejected("egress: scheme must be https");
        }

        InetAddress[] addresses;
        try {
            addresses = resolver.apply(host);
        } catch (RuntimeException e) {
            return EgressVerdict.dnsFailure("egress: DNS resolution failed for host");
        }
        if (addresses == null || addresses.length == 0) {
            return EgressVerdict.dnsFailure("egress: DNS resolution returned no address");
        }

        if (waived) {
            return EgressVerdict.allowed();
        }

        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                return EgressVerdict.policyRejected(
                        "egress: target resolves to a blocked range", address.getHostAddress());
            }
        }
        return EgressVerdict.allowed();
    }

    private static boolean isBlocked(InetAddress address) {
        InetAddress unwrapped = unwrapIpv4Mapped(address);
        return unwrapped.isLoopbackAddress()
                || unwrapped.isAnyLocalAddress()
                || unwrapped.isLinkLocalAddress()
                || unwrapped.isSiteLocalAddress()
                || unwrapped.isMulticastAddress()
                || isUniqueLocalIpv6(unwrapped);
    }

    /** {@code fc00::/7}: Java's {@code isSiteLocalAddress()} does not cover this IPv6 range. */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /** Unwraps an IPv4-mapped IPv6 address (e.g. {@code ::ffff:127.0.0.1}) before deciding. */
    private static InetAddress unwrapIpv4Mapped(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return address;
        }
        byte[] bytes = address.getAddress();
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return address;
            }
        }
        if ((bytes[10] & 0xFF) != 0xFF || (bytes[11] & 0xFF) != 0xFF) {
            return address;
        }
        byte[] ipv4 = new byte[4];
        System.arraycopy(bytes, 12, ipv4, 0, 4);
        try {
            return InetAddress.getByAddress(ipv4);
        } catch (UnknownHostException e) {
            // getByAddress never resolves DNS for a raw 4-byte array; cannot occur.
            throw new AssertionError(e);
        }
    }
}
