package com.shise.studio.common;

import java.util.*;
import org.slf4j.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class Errors {
  private static final Logger log = LoggerFactory.getLogger(Errors.class);

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<?> business(BusinessException e) {
    return fail(e.getStatus(), e.getMessage());
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<?> integrity(Exception e) {
    return fail(409, "记录重复，或仍被业务记录引用，请检查后重试");
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<?> large(Exception e) {
    return fail(413, "单个文件不能超过10MB");
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.multipart.support.MissingServletRequestPartException.class,
    org.springframework.web.bind.MethodArgumentNotValidException.class
  })
  public ResponseEntity<?> input(Exception e) {
    return fail(400, "请求参数格式不正确或缺少必填字段");
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  public ResponseEntity<?> missing(Exception e) {
    return fail(404, "接口或资源不存在");
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<?> method(Exception e) {
    return fail(405, "请求方法不支持");
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unknown(Exception e) {
    String id = UUID.randomUUID().toString().substring(0, 8);
    log.error("请求异常 {}", id, e);
    return fail(500, "服务暂时异常，请联系管理员，编号：" + id);
  }

  private ResponseEntity<?> fail(int status, String message) {
    return ResponseEntity.status(status).body(new ApiResponse<>(status, message, null));
  }
}
