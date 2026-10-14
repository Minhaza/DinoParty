package com.dinoparty.web;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.guest.AuthenticatedGuest;
import com.dinoparty.wordpacks.WordPackLimits;
import com.dinoparty.wordpacks.WordPackService;
import com.dinoparty.wordpacks.WordPackTypes.AddResult;
import com.dinoparty.wordpacks.WordPackTypes.ImportPreview;
import com.dinoparty.wordpacks.WordPackTypes.ImportResult;
import com.dinoparty.wordpacks.WordPackTypes.PackInfo;
import com.dinoparty.wordpacks.WordPackTypes.WordInput;
import com.dinoparty.wordpacks.WordPackTypes.WordsPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Room-scoped custom word packs (api-contracts §2.5). The caller always comes from the
 * authenticated principal; the service checks membership, host rights and that the pack belongs to
 * the room in the path. Word texts are only ever returned by the host-only endpoints.
 */
@RestController
@RequestMapping("/api/v1/rooms/{roomCode}/word-packs")
class WordPackController {

  record CreatePackRequest(
      @NotNull @Size(max = 400) String name, @Size(max = 2000) String description) {}

  record UpdatePackRequest(@Size(max = 400) String name, @Size(max = 2000) String description) {}

  record WordEntryRequest(
      @NotNull @Size(max = 1000) String text,
      @Size(max = 400) String category,
      @Size(max = 40) String difficulty) {}

  record AddWordsRequest(
      @NotNull @Size(min = 1, max = WordPackLimits.MAX_ADD_BATCH)
          List<@NotNull @Valid WordEntryRequest> words) {}

  record PacksDto(List<PackInfo> packs) {}

  private final WordPackService service;

  WordPackController(WordPackService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  PackInfo create(
      @AuthenticationPrincipal AuthenticatedGuest caller,
      @PathVariable String roomCode,
      @Valid @RequestBody CreatePackRequest request) {
    return service.create(caller.guestId(), roomCode, request.name(), request.description());
  }

  @GetMapping
  PacksDto list(@AuthenticationPrincipal AuthenticatedGuest caller, @PathVariable String roomCode) {
    return new PacksDto(service.list(caller.guestId(), roomCode));
  }

  @GetMapping("/{packId}")
  PackInfo get(
      @AuthenticationPrincipal AuthenticatedGuest caller,
      @PathVariable String roomCode,
      @PathVariable UUID packId) {
    return service.get(caller.guestId(), roomCode, packId);
  }

  @PatchMapping("/{packId}")
  PackInfo update(
      @AuthenticationPrincipal AuthenticatedGuest caller,
      @PathVariable String roomCode,
      @PathVariable UUID packId,
      @Valid @RequestBody UpdatePackRequest request) {
    return service.update(
        caller.guestId(), roomCode, packId, request.name(), request.description());
  }

  @DeleteMapping("/{packId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(
      @AuthenticationPrincipal AuthenticatedGuest caller,
      @PathVariable String roomCode,
      @PathVariable UUID packId) {
    service.delete(caller.guestId(), roomCode, packId);
  }

  @GetMapping("/{packId}/words")
  WordsPage words(
      @AuthenticationPrincipal AuthenticatedGuest caller,
      @PathVariable String roomCode,
      @PathVariable UUID packId,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "100") int limit) {
    return service.words(caller.guestId(), roomCode, packId, offset, limit);
  }

  @PostMapping("/{packId}/words")
  AddResult addWords(
      @AuthenticationPrincipal AuthenticatedGuest caller,
      @PathVariable String roomCode,
      @PathVariable UUID packId,
      @Valid @RequestBody AddWordsRequest request) {
    List<WordInput> words =
        request.words().stream()
            .map(w -> new WordInput(w.text(), w.category(), w.difficulty()))
            .toList();
    return service.addWords(caller.guestId(), roomCode, packId, words);
  }

  @DeleteMapping("/{packId}/words/{wordId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void removeWord(
      @AuthenticationPrincipal AuthenticatedGuest caller,
      @PathVariable String roomCode,
      @PathVariable UUID packId,
      @PathVariable UUID wordId) {
    service.removeWord(caller.guestId(), roomCode, packId, wordId);
  }

  @PostMapping(path = "/{packId}/imports/preview", consumes = "multipart/form-data")
  ImportPreview previewImport(
      @AuthenticationPrincipal AuthenticatedGuest caller,
      @PathVariable String roomCode,
      @PathVariable UUID packId,
      @RequestParam("file") MultipartFile file) {
    return service.preview(
        caller.guestId(), roomCode, packId, file.getOriginalFilename(), bytesOf(file));
  }

  @PostMapping(path = "/{packId}/imports/confirm", consumes = "multipart/form-data")
  ImportResult confirmImport(
      @AuthenticationPrincipal AuthenticatedGuest caller,
      @PathVariable String roomCode,
      @PathVariable UUID packId,
      @RequestParam("file") MultipartFile file,
      @RequestParam("sha256") String sha256,
      @RequestParam("packVersion") long packVersion) {
    return service.confirm(
        caller.guestId(),
        roomCode,
        packId,
        file.getOriginalFilename(),
        bytesOf(file),
        sha256,
        packVersion);
  }

  /** The size is checked before the bytes are read into memory. */
  private static byte[] bytesOf(MultipartFile file) {
    if (file.getSize() > WordPackLimits.MAX_FILE_BYTES) {
      throw new DomainException(ErrorCode.PAYLOAD_TOO_LARGE, "The file is larger than 1 MiB.");
    }
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
