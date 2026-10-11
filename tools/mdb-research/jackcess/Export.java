// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

import com.healthmarketscience.jackcess.*; import java.io.*; import java.util.*;
public class Export { public static void main(String[] a) throws Exception {
  try (Database db = new DatabaseBuilder(new File(a[0])).setReadOnly(true).open()) {
    for (String t : a[1].split(",")) { Table tb=db.getTable(t); List<String> lines=new ArrayList<>();
      for (Row r : tb) { StringBuilder sb=new StringBuilder(t+"|"); for (Column c: tb.getColumns()) { Object v=r.get(c.getName()); sb.append(c.getName()).append('=').append(norm(v)).append(';'); } lines.add(sb.toString()); }
      Collections.sort(lines); lines.forEach(System.out::println); } } }
  static String norm(Object v){ if(v==null) return ""; if (v instanceof java.math.BigDecimal b) return b.stripTrailingZeros().toPlainString(); if (v instanceof Boolean b) return b?"1":"0";
    if (v instanceof Double d) return String.format(java.util.Locale.ROOT,"%.6f",d); if (v instanceof java.time.LocalDateTime t) return t.toString().replace('T',' ');
    return v.toString().replace("\r","").replace("\n","\\n"); } }
