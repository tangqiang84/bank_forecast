package com.bankforecast.attachment;

import java.io.IOException;

public interface AttachmentStorage {
  String put(String objectKey, byte[] content) throws IOException;

  byte[] get(String objectKey) throws IOException;

  void delete(String objectKey) throws IOException;
}
