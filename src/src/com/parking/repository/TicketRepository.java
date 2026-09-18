package com.parking.repository;

import com.parking.model.Ticket;
import java.util.List;

/** Repository abstraction (Interface) - swap implementations without touching the service. */
public interface TicketRepository {
    void save(Ticket ticket);
    List<Ticket> findAll();
}
