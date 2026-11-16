package com.shise.studio.catalog;

import com.shise.studio.common.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CatalogController {
  private final CatalogService service;

  public CatalogController(CatalogService service) {
    this.service = service;
  }

  @GetMapping("/{type:students|teachers|courses|classes|rooms}")
  public ApiResponse<?> list(@PathVariable String type) {
    return ApiResponse.ok(service.list(type));
  }

  @GetMapping("/{type:students|teachers|courses|classes|rooms}/{id}")
  public ApiResponse<?> get(@PathVariable String type, @PathVariable long id) {
    return ApiResponse.ok(service.detail(type, id));
  }

  @PostMapping("/{type:students|teachers|courses|classes|rooms}")
  public ApiResponse<?> create(@PathVariable String type, @RequestBody Map<String, Object> p) {
    return ApiResponse.ok(service.create(type, p));
  }

  @PutMapping("/{type:students|teachers|courses|classes|rooms}/{id}")
  public ApiResponse<?> update(
      @PathVariable String type, @PathVariable long id, @RequestBody Map<String, Object> p) {
    return ApiResponse.ok(service.update(type, id, p));
  }

  @DeleteMapping("/{type:students|teachers|courses|classes|rooms}/{id}")
  public ApiResponse<?> delete(@PathVariable String type, @PathVariable long id) {
    service.delete(type, id);
    return ApiResponse.ok();
  }

  @GetMapping("/users")
  public ApiResponse<?> users() {
    return ApiResponse.ok(service.users());
  }

  @PostMapping("/users")
  public ApiResponse<?> createUser(@RequestBody Map<String, Object> p) {
    return ApiResponse.ok(service.createUser(p));
  }

  @PutMapping("/users/{id}")
  public ApiResponse<?> updateUser(@PathVariable long id, @RequestBody Map<String, Object> p) {
    return ApiResponse.ok(service.updateUser(id, p));
  }
}
