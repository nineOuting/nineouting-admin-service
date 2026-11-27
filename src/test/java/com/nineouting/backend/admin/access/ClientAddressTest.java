package com.nineouting.backend.admin.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 누가 머리글을 꾸미거나 ALB로 바로 붙어도 실제 주소가 잡히는지 */
class ClientAddressTest {

    private static final List<Cidr> CLOUDFRONT = List.of(Cidr.parse("130.176.0.0/16"), Cidr.parse("2600:9000::/28"));
    private static final String ALB = "10.20.0.15";

    @Test
    void CloudFront를_거치면_CloudFront가_본_주소() throws Exception {
        assertThat(client("203.0.113.5, 130.176.10.1", ALB)).isEqualTo("203.0.113.5");
        assertThat(client("203.0.113.5, 2600:9000:1::1", ALB)).isEqualTo("203.0.113.5");
    }

    @Test
    void 꾸민_머리글은_무시하고_실제_주소() throws Exception {
        // CloudFront를 거치며 앞에 가짜 주소를 넣은 경우
        assertThat(client("1.2.3.4, 198.51.100.7, 130.176.10.1", ALB)).isEqualTo("198.51.100.7");
        // ALB로 바로 붙으며 허용 주소와 CloudFront 주소를 꾸민 경우
        assertThat(client("203.0.113.5, 130.176.10.1, 198.51.100.7", ALB)).isEqualTo("198.51.100.7");
        // 머리글 없이 바로
        assertThat(client(null, "198.51.100.7")).isEqualTo("198.51.100.7");
    }

    @Test
    void 주소가_아닌_값이_끼면_믿지_않는다() {
        assertThat(IpAccessInterceptor.clientAddress("evil.example.com, 130.176.10.1", ALB, CLOUDFRONT)).isEmpty();
    }

    @Test
    void 대역_계산() {
        assertThat(Cidr.parse("112.147.228.180/32").contains(bytes("112.147.228.180"))).isTrue();
        assertThat(Cidr.parse("112.147.228.180/32").contains(bytes("112.147.228.181"))).isFalse();
        assertThat(Cidr.parse("10.20.0.0/16").contains(bytes("10.20.255.1"))).isTrue();
        assertThat(Cidr.parse("10.20.0.0/16").contains(bytes("10.21.0.1"))).isFalse();
        assertThat(Cidr.parse("120.52.22.96/27").contains(bytes("120.52.22.127"))).isTrue();
        assertThat(Cidr.parse("120.52.22.96/27").contains(bytes("120.52.22.128"))).isFalse();
        assertThat(Cidr.parse("203.0.113.9").contains(bytes("203.0.113.9"))).isTrue();
        assertThat(Cidr.parse("2600:9000::/28").contains(bytes("2600:900f::1"))).isTrue();
        assertThat(Cidr.parse("2600:9000::/28").contains(bytes("203.0.113.9"))).isFalse();
        assertThatThrownBy(() -> Cidr.parse("10.0.0.0/33")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cidr.parse("example.com")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ip_ranges_json에서_CloudFront만_고른다() {
        String json = """
                {"prefixes":[{"ip_prefix":"130.176.0.0/16","service":"CLOUDFRONT"},{"ip_prefix":"3.5.0.0/16","service":"S3"}],
                 "ipv6_prefixes":[{"ipv6_prefix":"2600:9000::/28","service":"CLOUDFRONT"}]}""";
        List<Cidr> ranges = CloudFrontRanges.parse(JsonMapper.builder().build().readTree(json));
        assertThat(ranges).hasSize(2);
    }

    private static String client(String forwarded, String remote) throws Exception {
        byte[] address = IpAccessInterceptor.clientAddress(forwarded, remote, CLOUDFRONT).orElseThrow();
        return InetAddress.getByAddress(address).getHostAddress().replace("0:0:0:0:0:0:0:1", "::1");
    }

    private static byte[] bytes(String ip) {
        return Cidr.toBytes(ip);
    }
}
