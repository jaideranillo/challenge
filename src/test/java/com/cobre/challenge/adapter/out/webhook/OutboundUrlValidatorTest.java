package com.cobre.challenge.adapter.out.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.adapter.out.webhook.config.EgressProperties;
import com.cobre.challenge.adapter.out.webhook.dto.EgressVerdict;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class OutboundUrlValidatorTest {

    private static final EgressProperties NO_ALLOWLIST = new EgressProperties(List.of());
    private static final EgressProperties LOCALHOST_ALLOWLIST =
            new EgressProperties(List.of("localhost"));

    @Test
    void defaultConfigurationRejectsEveryPrivateOrReservedTarget() {
        Function<String, InetAddress[]> resolver = fakeResolver(Map.of(
                "localhost", addrs(ip("127.0.0.1")),
                "127.0.0.1", addrs(ip("127.0.0.1")),
                "::1", addrs(ip("::1")),
                "10.0.0.5", addrs(ip("10.0.0.5")),
                "172.16.0.1", addrs(ip("172.16.0.1")),
                "192.168.1.10", addrs(ip("192.168.1.10")),
                "169.254.169.254", addrs(ip("169.254.169.254")),
                "internal.example.test", addrs(ip("10.0.0.9"))));
        OutboundUrlValidator validator = newValidator(NO_ALLOWLIST, false, resolver);

        assertRejected(validator, "https://localhost/hook");
        assertRejected(validator, "https://127.0.0.1/hook");
        assertRejected(validator, "https://[::1]/hook");
        assertRejected(validator, "https://10.0.0.5/hook");
        assertRejected(validator, "https://172.16.0.1/hook");
        assertRejected(validator, "https://192.168.1.10/hook");
        assertRejected(validator, "https://169.254.169.254/latest/meta-data/");
        assertRejected(validator, "https://internal.example.test/hook");
    }

    @Test
    void httpSchemeIsRejectedForSchemeAloneBeforeResolution() {
        Function<String, InetAddress[]> resolver = host -> {
            throw new AssertionError("must not resolve when the scheme alone is rejected");
        };
        OutboundUrlValidator validator = newValidator(NO_ALLOWLIST, false, resolver);

        assertRejected(validator, "http://example.com/hook");
    }

    @Test
    void hostnameResolvingToPublicAndPrivateAddressIsRejected() {
        Function<String, InetAddress[]> resolver = fakeResolver(Map.of(
                "mixed.example.test", addrs(ip("93.184.216.34"), ip("10.1.1.1"))));
        OutboundUrlValidator validator = newValidator(NO_ALLOWLIST, false, resolver);

        assertRejected(validator, "https://mixed.example.test/hook");
    }

    @Test
    void ipv4MappedLoopbackIsUnwrappedAndRejected() throws UnknownHostException {
        InetAddress mapped = InetAddress.getByAddress("mapped.example.test", new byte[] {
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 127, 0, 0, 1
        });
        Function<String, InetAddress[]> resolver =
                fakeResolver(Map.of("mapped.example.test", new InetAddress[] {mapped}));
        OutboundUrlValidator validator = newValidator(NO_ALLOWLIST, false, resolver);

        assertRejected(validator, "https://mapped.example.test/hook");
    }

    @Test
    void dnsFailureReturnsFailureVerdictNeverPolicyRejected() {
        Function<String, InetAddress[]> resolver = host -> {
            throw new UncheckedIOException(new UnknownHostException("nxdomain"));
        };
        OutboundUrlValidator validator = newValidator(NO_ALLOWLIST, false, resolver);

        EgressVerdict verdict = validator.validate("https://nxdomain.example.test/hook");

        assertThat(verdict.state()).isEqualTo(EgressVerdict.State.DNS_FAILURE);
        assertThat(verdict.reason()).isNotBlank();
        assertThat(verdict.resolvedAddress()).isEmpty();
    }

    @Test
    void publicHttpsHostResolvingOnlyToPublicAddressesIsAllowed() {
        Function<String, InetAddress[]> resolver =
                fakeResolver(Map.of("api.example.com", addrs(ip("93.184.216.34"))));
        OutboundUrlValidator validator = newValidator(NO_ALLOWLIST, false, resolver);

        assertThat(validator.validate("https://api.example.com/hook").state())
                .isEqualTo(EgressVerdict.State.ALLOWED);
    }

    @Test
    void allowlistedHostUnderLocalProfileBypassesRangeCheckButOtherPrivateHostsAreStillRejected() {
        Function<String, InetAddress[]> resolver = fakeResolver(Map.of(
                "localhost", addrs(ip("127.0.0.1")),
                "other-private.example.test", addrs(ip("10.5.5.5"))));
        OutboundUrlValidator validator = newValidator(LOCALHOST_ALLOWLIST, true, resolver);

        assertThat(validator.validate("https://localhost/hook").state())
                .isEqualTo(EgressVerdict.State.ALLOWED);
        assertThat(validator.validate("https://other-private.example.test/hook").state())
                .isEqualTo(EgressVerdict.State.POLICY_REJECTED);
    }

    @Test
    void resolvesOnEveryCallWithNoCachingOfAPreviousVerdict() {
        AtomicInteger calls = new AtomicInteger();
        Function<String, InetAddress[]> resolver = host -> {
            calls.incrementAndGet();
            return addrs(ip("93.184.216.34"));
        };
        OutboundUrlValidator validator = newValidator(NO_ALLOWLIST, false, resolver);

        validator.validate("https://api.example.com/hook");
        validator.validate("https://api.example.com/hook");

        assertThat(calls.get()).isEqualTo(2);
    }

    // --- 19a: the allowlist waives HTTPS too, only under the local profile ---

    private static final Function<String, InetAddress[]> LOCALHOST_RESOLVER =
            fakeResolver(Map.of("localhost", addrs(ip("127.0.0.1"))));

    @Test
    void defaultProfileNotAllowlistedHttpIsRejected() {
        OutboundUrlValidator validator = newValidator(NO_ALLOWLIST, false, LOCALHOST_RESOLVER);

        assertRejected(validator, "http://localhost/hook");
    }

    @Test
    void defaultProfileAllowlistedHttpIsStillRejectedBecauseProfileConditionFails() {
        OutboundUrlValidator validator =
                newValidator(LOCALHOST_ALLOWLIST, false, LOCALHOST_RESOLVER);

        assertRejected(validator, "http://localhost/hook");
    }

    @Test
    void localProfileNotAllowlistedHttpIsStillRejectedBecauseListConditionFails() {
        OutboundUrlValidator validator = newValidator(NO_ALLOWLIST, true, LOCALHOST_RESOLVER);

        assertRejected(validator, "http://localhost/hook");
    }

    @Test
    void localProfileAllowlistedHttpIsAllowed() {
        OutboundUrlValidator validator =
                newValidator(LOCALHOST_ALLOWLIST, true, LOCALHOST_RESOLVER);

        assertThat(validator.validate("http://localhost/hook").state())
                .isEqualTo(EgressVerdict.State.ALLOWED);
    }

    // --- 19b: EgressVerdict carries the resolved address on a range-based rejection ---

    @Test
    void resolvedAddressIsEmptyOnASchemeRejection() {
        OutboundUrlValidator validator = newValidator(NO_ALLOWLIST, false, host -> {
            throw new AssertionError("must not resolve when the scheme alone is rejected");
        });

        EgressVerdict verdict = validator.validate("http://example.com/hook");

        assertThat(verdict.state()).isEqualTo(EgressVerdict.State.POLICY_REJECTED);
        assertThat(verdict.resolvedAddress()).isEmpty();
    }

    @Test
    void resolvedAddressIsEmptyOnADnsFailure() {
        OutboundUrlValidator validator = newValidator(NO_ALLOWLIST, false, host -> {
            throw new UncheckedIOException(new UnknownHostException("nxdomain"));
        });

        EgressVerdict verdict = validator.validate("https://nxdomain.example.test/hook");

        assertThat(verdict.state()).isEqualTo(EgressVerdict.State.DNS_FAILURE);
        assertThat(verdict.resolvedAddress()).isEmpty();
    }

    @Test
    void resolvedAddressCarriesTheFirstOffendingAddressWhenSeveralResolve() {
        Function<String, InetAddress[]> resolver = fakeResolver(Map.of(
                "mixed.example.test", addrs(ip("93.184.216.34"), ip("10.1.1.1"), ip("10.2.2.2"))));
        OutboundUrlValidator validator = newValidator(NO_ALLOWLIST, false, resolver);

        EgressVerdict verdict = validator.validate("https://mixed.example.test/hook");

        assertThat(verdict.state()).isEqualTo(EgressVerdict.State.POLICY_REJECTED);
        assertThat(verdict.resolvedAddress()).contains("10.1.1.1");
    }

    private static OutboundUrlValidator newValidator(
            EgressProperties properties,
            boolean localProfileActive,
            Function<String, InetAddress[]> resolver) {
        return new OutboundUrlValidator(properties, localProfileActive, resolver);
    }

    private static void assertRejected(OutboundUrlValidator validator, String url) {
        assertThat(validator.validate(url).state()).isEqualTo(EgressVerdict.State.POLICY_REJECTED);
    }

    private static Function<String, InetAddress[]> fakeResolver(Map<String, InetAddress[]> byHost) {
        return host -> {
            InetAddress[] addresses = byHost.get(host);
            if (addresses == null) {
                throw new UncheckedIOException(new UnknownHostException(host));
            }
            return addresses;
        };
    }

    private static InetAddress[] addrs(InetAddress... addresses) {
        return addresses;
    }

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new AssertionError("literal address must always parse: " + literal, e);
        }
    }
}
