package edu.cmpe272.portal.messaging;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

@Converter(autoApply = true)
public class MessageSourceConverter implements AttributeConverter<MessageSource, String> {

    @Override
    public String convertToDatabaseColumn(MessageSource source) {
        return source == null ? null : source.dbValue();
    }

    @Override
    public MessageSource convertToEntityAttribute(String value) {
        return value == null ? null : MessageSource.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
