package app.series;

import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.lang.NonNull;

public class CsvHttpMessageConverter extends AbstractHttpMessageConverter<Object> {
  private static final MediaType TEXT_CSV = MediaType.valueOf("text/csv");
  private final CsvMapper mapper = new CsvMapper();

  public CsvHttpMessageConverter() {
    super(TEXT_CSV);
    mapper.findAndRegisterModules();
  }

  @Override
  protected boolean supports(@NonNull Class<?> clazz) {
    return Collection.class.isAssignableFrom(clazz) || clazz.isArray();
  }

  @Override
  @NonNull
  protected Object readInternal(@NonNull Class<?> clazz, @NonNull HttpInputMessage inputMessage)
      throws IOException, HttpMessageNotReadableException {
    throw new HttpMessageNotReadableException("CSV reading not supported", inputMessage);
  }

  @Override
  protected void writeInternal(@NonNull Object object, @NonNull HttpOutputMessage outputMessage)
      throws IOException, HttpMessageNotWritableException {
    CsvSchema schema = determineSchema(object);
    var writer = mapper.writer(schema).writeValues(outputMessage.getBody());
    if (object instanceof Collection<?> collection) {
      for (Object element : collection) {
        writer.write(element);
      }
    } else if (object != null && object.getClass().isArray()) {
      int length = Array.getLength(object);
      for (int i = 0; i < length; i++) {
        writer.write(Array.get(object, i));
      }
    }
    writer.flush();
  }

  private CsvSchema determineSchema(Object value) {
    Iterable<?> iterable = null;
    if (value instanceof Collection<?> collection) {
      iterable = collection;
    } else if (value != null && value.getClass().isArray()) {
      int length = Array.getLength(value);
      List<Object> elements = new ArrayList<>(length);
      for (int i = 0; i < length; i++) {
        elements.add(Array.get(value, i));
      }
      iterable = elements;
    }

    if (iterable != null) {
      Object sample = null;
      for (Object element : iterable) {
        if (element != null) {
          sample = element;
          break;
        }
      }

      if (sample instanceof Map<?, ?>) {
        CsvSchema schema = buildSchemaFromMaps(iterable);
        if (schema != null) {
          return schema;
        }
      }

      if (sample != null) {
        return mapper.schemaFor(sample.getClass()).withHeader();
      }
    }

    return mapper.schemaFor(Object.class).withHeader();
  }

  private CsvSchema buildSchemaFromMaps(Iterable<?> iterable) {
    Set<String> columns = new LinkedHashSet<>();
    for (Object element : iterable) {
      if (element instanceof Map<?, ?> map) {
        for (Object key : map.keySet()) {
          if (key != null) {
            columns.add(key.toString());
          }
        }
      }
    }
    if (columns.isEmpty()) {
      return null;
    }
    CsvSchema.Builder builder = CsvSchema.builder();
    columns.forEach(builder::addColumn);
    return builder.setUseHeader(true).build();
  }
}
