package awscat;

import java.lang.reflect.Field;

import org.springframework.util.*;

public abstract class AbstractPluginProvider {

  private final String base;
  private final Class<?> classOfOptions;

  public AbstractPluginProvider(String base, Class<?> classOfOptions) {
    this.base = base;
    this.classOfOptions = classOfOptions;
  }

  public String name() {
    return ClassUtils.getShortName(getClass());
  }

  public String help() {
    String options = "";
    for (Field field : classOfOptions.getFields())
      options += String.format(",%s", field.getName());
    return String.format("%s[%s]", base, options);
  }

}
