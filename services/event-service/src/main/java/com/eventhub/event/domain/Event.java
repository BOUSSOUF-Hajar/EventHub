package com.eventhub.event.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue
    private UUID id;

    @NotBlank
    private String title;

    private String description;

    @NotBlank
    private String venue;

    @NotNull
    private Instant startsAt;

    @NotNull
    @Min(0)
    private Integer totalCapacity;

    // Decremente par le Booking Service via l'evenement "booking.confirmed".
    // Conserve ici en lecture rapide pour l'affichage du catalogue,
    // la verite "ecriture" sur la disponibilite vit dans le Booking Service.
    @NotNull
    @Min(0)
    private Integer remainingSeats;

    protected Event() {
        // requis par JPA
    }

    public Event(String title, String description, String venue, Instant startsAt, Integer totalCapacity) {
        this.title = title;
        this.description = description;
        this.venue = venue;
        this.startsAt = startsAt;
        this.totalCapacity = totalCapacity;
        this.remainingSeats = totalCapacity;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVenue() {
        return venue;
    }

    public void setVenue(String venue) {
        this.venue = venue;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public void setStartsAt(Instant startsAt) {
        this.startsAt = startsAt;
    }

    public Integer getTotalCapacity() {
        return totalCapacity;
    }

    public void setTotalCapacity(Integer totalCapacity) {
        this.totalCapacity = totalCapacity;
    }

    public Integer getRemainingSeats() {
        return remainingSeats;
    }

    public void setRemainingSeats(Integer remainingSeats) {
        this.remainingSeats = remainingSeats;
    }
}
