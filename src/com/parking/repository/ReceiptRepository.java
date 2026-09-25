package com.parking.repository;

import java.util.Optional;

/** Payment receipt screenshots uploaded by drivers or the gate, kept as evidence for the manager. */
public interface ReceiptRepository {
    final class Receipt {
        private final String ticketId, contentType, sha256;
        private final byte[] data;
        public Receipt(String ticketId, String contentType, byte[] data, String sha256) {
            this.ticketId = ticketId; this.contentType = contentType; this.data = data; this.sha256 = sha256;
        }
        public String ticketId() { return ticketId; }
        public String contentType() { return contentType; }
        public byte[] data() { return data; }
        public String sha256() { return sha256; }
    }
    void save(Receipt receipt);
    Optional<Receipt> find(String ticketId);
    boolean hashUsed(String sha256);
}
