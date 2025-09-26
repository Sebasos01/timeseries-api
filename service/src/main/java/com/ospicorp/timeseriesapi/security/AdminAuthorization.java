package com.ospicorp.timeseriesapi.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component("adminAuthorization")
public class AdminAuthorization {
  private final String scope;

  public AdminAuthorization(@Value("${security.admin.scope:admin:reindex}") String scope) {
    this.scope = scope;
  }

  public String scope() {
    return scope;
  }
}

