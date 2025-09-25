package app.series;

import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import java.io.IOException;
import java.util.Collection;
import java.util.Objects;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

public class CsvHttpMessageConverter extends AbstractHttpMessageConverter<Object> {
  private static final MediaType TEXT_CSV = MediaType.valueOf("text/csv");
  private final CsvMapper mapper = new CsvMapper();

  public CsvHttpMessageConverter() {
    super(TEXT_CSV);
    mapper.findAndRegisterModules();
  }

  @Override
  protected boolean supports(Class<?> clazz) {
    return Collection.class.isAssignableFrom(clazz) || clazz.isArray();
  }

  @Override
  protected Object readInternal(Class<?> clazz, HttpInputMessage inputMessage)
      throws IOException, HttpMessageNotReadableException {
    throw new HttpMessageNotReadableException("CSV reading not supported", inputMessage);
  }

  @Override
  protected void writeInternal(Object object, HttpOutputMessage outputMessage)
      throws IOException, HttpMessageNotWritableException {
    CsvSchema schema = determineSchema(object);
    var writer = mapper.writer(schema).writeValues(outputMessage.getBody());
    if (object instanceof Collection<?> collection) {
      for (Object element : collection) {
        writer.write(element);
      }
    } else if (object != null && object.getClass().isArray()) {
      int length = java.lang.reflect.Array.getLength(object);
      for (int i = 0; i < length; i++) {
        writer.write(java.lang.reflect.Array.get(object, i));
      }
    }
    writer.flush();
  }

  private CsvSchema determineSchema(Object value) {
    Object sample = null;
    if (value instanceof Collection<?> collection) {
      sample = collection.stream().filter(Objects::nonNull).findFirst().orElse(null);
    } else if (value != null && value.getClass().isArray()) {
      int length = java.lang.reflect.Array.getLength(value);
      for (int i = 0; i < length; i++) {
        Object element = java.lang.reflect.Array.get(value, i);
        if (element != null) {
          sample = element;
          break;
        }
      }
    }
    if (sample == null) {
      return mapper.schemaFor(Object.class).withHeader();
    }
    return mapper.schemaFor(sample.getClass()).withHeader();
  }
}