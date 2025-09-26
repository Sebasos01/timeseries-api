package app.series;

public class InvalidParameterException extends RuntimeException {
  private final int errorCode;
  private final String moreInfo;

  public InvalidParameterException(String message, int errorCode, String moreInfo) {
    super(message);
    this.errorCode = errorCode;
    this.moreInfo = moreInfo;
  }

  public int errorCode() {
    return errorCode;
  }

  public String moreInfo() {
    return moreInfo;
  }
}
