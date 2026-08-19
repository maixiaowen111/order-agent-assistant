package com.example.order.exception;

  import lombok.Getter;

  @Getter
  public class BusinessException extends RuntimeException {

      private final Integer code;

      public BusinessException(Integer code, String message) {
          super(message);  // 传给父类，调用 getMessage() 就能拿到
          this.code = code;
      }

      public BusinessException(String message) {
          super(message);
          this.code = 500;
      }
  }