import com.healthmarketscience.jackcess.*;
import java.io.*; import java.util.*; import java.time.*; import java.math.BigDecimal;
public class WriteTest {
  public static void main(String[] a) throws Exception {
    File f = new File(a[0]); Random rnd = new Random(42);
    try (Database db = new DatabaseBuilder(f).open()) {
      Table dd = db.getTable("DvizhenieDeneg"), gb = db.getTable("GroupBudget"), co = db.getTable("Course"), acc = db.getTable("Account");
      LocalDateTime now = LocalDateTime.of(2026,10,10,12,0);
      // dictionaries
      Map<String,Object> m = new HashMap<>();
      for (int i=0;i<5;i++){ m.clear(); m.put("Editor",3); m.put("Creator",3); m.put("DateEdit",now); m.put("DateCreate",now); m.put("del",false); m.put("Parent_ID", 2); m.put("Name","Тест статья "+i+" ёЁ"); m.put("Ord", 200+i); gb.addRowFromMap(m);}
      m.clear(); m.put("Editor",3); m.put("Creator",3); m.put("DateEdit",now); m.put("DateCreate",now); m.put("del",false); m.put("Name","Карта Тинькофф"); acc.addRowFromMap(m);
      // operations
      int[][] types={{2,2},{1,1},{8,72}};
      for (int i=0;i<600;i++){
        int[] t=types[rnd.nextInt(3)];
        m.clear(); m.put("Editor",3); m.put("Creator",3); m.put("DateEdit",now); m.put("DateCreate",now); m.put("del",false);
        m.put("Date", LocalDateTime.of(2024+rnd.nextInt(3),1+rnd.nextInt(12),1+rnd.nextInt(28),0,0));
        m.put("Summa", new BigDecimal(rnd.nextInt(10000000)).movePointLeft(2));
        m.put("Currency_ID", rnd.nextInt(5)==0?10:12); m.put("TypeOper_ID",t[0]);
        m.put("GroupBudget_ID", t[0]==2? 44 : t[0]==1 ? 3 : 72);
        if (t[0]!=1) m.put("AccountFrom", 1+rnd.nextInt(6));
        if (t[0]!=2) m.put("AccountTo", 1+rnd.nextInt(6));
        if (rnd.nextBoolean()) m.put("People_ID", 4+rnd.nextInt(4));
        int kind=rnd.nextInt(10);
        if (kind<5) m.put("Comment","Покупка №"+i);
        else if (kind<8) m.put("Comment","Длинный комментарий ".repeat(10+rnd.nextInt(20))+i);
        else if (kind==8) m.put("Comment","Очень длинный ".repeat(400)+i); // >4KB -> multi-page LVAL
        dd.addRowFromMap(m);
      }
      // updates: grow comments, change sums, soft delete
      int n=0;
      for (Row r : CursorBuilder.createCursor(dd)) {
        int id=(Integer)r.get("id"); if (id<1583) continue;
        if (n%7==0){ r.put("Comment", "Изменено ".repeat(50+n%30)+id); r.put("DateEdit", now.plusMinutes(1)); dd.updateRow(r);}
        else if (n%11==0){ r.put("del", true); dd.updateRow(r);}
        else if (n%13==0){ r.put("Summa", new BigDecimal("12345.67")); r.put("Comment", null); dd.updateRow(r);}
        n++;
      }
      // courses: insert 400, hard-delete 30
      for (int i=0;i<400;i++){ m.clear(); m.put("Editor",3); m.put("Creator",3); m.put("DateEdit",now); m.put("DateCreate",now); m.put("del",false);
        m.put("Currency_ID", new int[]{10,11,17}[i%3]); m.put("Date", LocalDateTime.of(2026,1,1,0,0).plusDays(i/3)); m.put("Course", 80+rnd.nextDouble()*20); co.addRowFromMap(m);}
      int del=0; Cursor c=CursorBuilder.createCursor(co);
      for (Row r : c) { if ((Integer)r.get("id")%13==0 && del<30) { c.deleteCurrentRow(); del++; } }
      System.out.println("done: dd rows="+dd.getRowCount()+" gb="+gb.getRowCount()+" course="+co.getRowCount()+" acc="+acc.getRowCount());
    }
  }
}
