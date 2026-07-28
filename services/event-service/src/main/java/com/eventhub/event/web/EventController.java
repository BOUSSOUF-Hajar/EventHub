package com.eventhub.event.web;

import com.eventhub.event.domain.Event;
import com.eventhub.event.repository.EventRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventRepository eventRepository;

    public EventController(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    // Lecture publique : pas d'authentification requise (voir SecurityConfig du Gateway)
    @GetMapping
    public List<Event> findAll() {
        return eventRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Event> findById(@PathVariable UUID id) {
        return eventRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // TODO: remplacer ce DTO d'entree simplifie par un EventCreateRequest dedie
    // une fois la validation metier (date future, capacite > 0) ajoutee.
    @PostMapping
    @PreAuthorize("hasRole('ORGANIZER') or hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public Event create(@Valid @RequestBody Event event) {
        return eventRepository.save(event);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ORGANIZER') or hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        eventRepository.deleteById(id);
    }
}
