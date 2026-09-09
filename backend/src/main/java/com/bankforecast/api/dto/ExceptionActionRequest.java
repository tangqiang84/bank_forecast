package com.bankforecast.api.dto;

import javax.validation.constraints.Size;

public class ExceptionActionRequest {
  private Long ownerUserId;

  @Size(max = 512, message = "处理说明不能超过512个字符")
  private String text;

  public Long getOwnerUserId() {
    return ownerUserId;
  }

  public void setOwnerUserId(Long ownerUserId) {
    this.ownerUserId = ownerUserId;
  }

  public String getText() {
    return text;
  }

  public void setText(String text) {
    this.text = text;
  }
}
