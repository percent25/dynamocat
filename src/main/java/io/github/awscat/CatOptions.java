package io.github.awscat;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;

public class CatOptions {
  public String filter = "true";
  public final List<String> action = new ArrayList<>();
  public String toString() {
    return new Gson().toJson(this);
  }
}