package com.nineouting.backend.admin.access;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** IPv4, IPv6 대역 하나 (예: 112.147.228.180/32, 2600:9000::/28). 대역 없이 주소만 주면 그 주소 하나 */
public record Cidr(byte[] network, int prefix) {

    public static Cidr parse(String value) {
        String text = value.trim();
        int slash = text.indexOf('/');
        byte[] address = toBytes(slash < 0 ? text : text.substring(0, slash));
        int prefix = slash < 0 ? address.length * 8 : Integer.parseInt(text.substring(slash + 1));
        if (prefix < 0 || prefix > address.length * 8) {
            throw new IllegalArgumentException("잘못된 대역: " + value);
        }
        return new Cidr(address, prefix);
    }

    public boolean contains(byte[] address) {
        if (address.length != network.length) {
            return false;
        }
        int fullBytes = prefix / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (address[i] != network[i]) {
                return false;
            }
        }
        int rest = prefix % 8;
        if (rest == 0) {
            return true;
        }
        int mask = 0xFF << (8 - rest) & 0xFF;
        return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    /** 이름 조회 없이 숫자 주소만 받는다 (DNS로 바꾸지 않음) */
    static byte[] toBytes(String address) {
        String text = address.trim();
        if (text.isEmpty() || !(text.contains(":") || text.matches("[0-9.]+"))) {
            throw new IllegalArgumentException("IP 주소가 아닙니다: " + address);
        }
        try {
            return InetAddress.getByName(text).getAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("IP 주소가 아닙니다: " + address, e);
        }
    }
}
