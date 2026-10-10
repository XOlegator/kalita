import io.github.spannm.jackcess.*; import java.io.*; import java.util.*; import java.time.*; import java.math.BigDecimal;
public class Var { public static void main(String[] a) throws Exception {
  String mode=a[1]; int n=Integer.parseInt(a[2]);
  try (Database db = DatabaseBuilder.open(new File(a[0]))) {
    Table dd=db.getTable("DvizhenieDeneg"); LocalDateTime now=LocalDateTime.of(2026,10,10,12,0,0);
    Map<String,Object> m=new HashMap<>(); Random rnd=new Random(1);
    for (int i=0;i<n;i++){ m.clear(); m.put("Editor",3); m.put("Creator",3); m.put("DateEdit",now); m.put("DateCreate",now); m.put("del",false);
      m.put("Date", LocalDateTime.of(2026,10,1+(i%9),0,0)); m.put("Summa", new BigDecimal(100+i)); m.put("Currency_ID",12); m.put("TypeOper_ID",2); m.put("GroupBudget_ID",44); m.put("AccountFrom",1); m.put("People_ID",4);
      if (mode.equals("short")) m.put("Comment","Тест "+i);
      if (mode.equals("long")) m.put("Comment","Длинный ".repeat(i%2==0?60:600)+i);
      dd.addRowFromMap(m);}
    System.out.println("rows="+dd.getRowCount());
  } } }
