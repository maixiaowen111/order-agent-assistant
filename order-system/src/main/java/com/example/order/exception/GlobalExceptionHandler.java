 package com.example.order.exception;

  import com.example.order.common.Result;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.web.bind.annotation.ExceptionHandler;
  import org.springframework.web.bind.annotation.RestControllerAdvice;

  /**
   * 全局异常处理器
   *
   * 设计原因：
   * 1. 不用在每个 Controller 里写 try-catch
   * 2. 统一异常处理，保证返回格式一致
   * 3. 区分「业务异常」和「系统异常」，返回不同错误码
   */
  @Slf4j
  @RestControllerAdvice
  public class GlobalExceptionHandler {

      /**
       * 处理业务异常
       * 场景：库存不足、密码错误、订单状态不允许支付等
       */
      @ExceptionHandler(BusinessException.class)
      public Result<?> handleBusinessException(BusinessException e) {
          log.warn("业务异常：code={}, message={}", e.getCode(), e.getMessage());
          return Result.fail(e.getCode(), e.getMessage());
      }

      /**
       * 处理参数校验异常
       * 场景：@NotNull、@NotBlank 校验不通过
       */
      @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
      public Result<?> handleValidException(org.springframework.web.bind.MethodArgumentNotValidException e) {
          String message = e.getBindingResult().getFieldError().getDefaultMessage();
          log.warn("参数校验失败：{}", message);
          return Result.fail(400, message);
      }

      /**
       * 处理其他未捕获异常（兜底）
       * 场景：NullPointerException、SQLException 等意料之外的错误
       */
      @ExceptionHandler(Exception.class)
      public Result<?> handleException(Exception e) {
          log.error("系统异常：", e);
          return Result.fail(500, "服务器内部错误");
      }
  }