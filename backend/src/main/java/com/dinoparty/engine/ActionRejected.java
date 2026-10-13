package com.dinoparty.engine;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;

/** A module refuses a command; the state must be exactly as it was before the call. */
public class ActionRejected extends DomainException {

  public ActionRejected(ErrorCode code, String message) {
    super(code, message);
  }
}
