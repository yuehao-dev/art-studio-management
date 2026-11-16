package com.shise.studio.profile;

import com.shise.studio.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/profile/avatar")
public class ProfileController {
  private final ProfileService profile;

  public ProfileController(ProfileService profile) {
    this.profile = profile;
  }

  @GetMapping
  public ApiResponse<?> avatar() {
    return ApiResponse.ok(profile.avatar());
  }

  @PostMapping
  public ApiResponse<?> replace(@RequestParam("file") MultipartFile file) {
    return ApiResponse.ok(profile.replaceAvatar(file));
  }

  @DeleteMapping
  public ApiResponse<?> clear() {
    return ApiResponse.ok(profile.clearAvatar());
  }
}
