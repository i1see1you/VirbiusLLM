package io.virbius.control.repository;

import io.virbius.control.domain.enums.AccessListDimension;
import io.virbius.control.domain.enums.AccessListPolarity;
import java.util.List;
import java.util.Map;

public interface AccessListRepository {

    List<String> list(String tenantId, AccessListPolarity polarity, AccessListDimension dimension);

    Map<String, List<String>> listAll(String tenantId);

    void replaceAll(String tenantId, AccessListPolarity polarity, AccessListDimension dimension, List<String> values);

    boolean add(String tenantId, AccessListPolarity polarity, AccessListDimension dimension, String value);

    boolean remove(String tenantId, AccessListPolarity polarity, AccessListDimension dimension, String value);
}