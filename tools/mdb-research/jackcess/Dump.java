import com.healthmarketscience.jackcess.*;
import com.healthmarketscience.jackcess.query.*;
import java.io.File; import java.util.*;
public class Dump {
  public static void main(String[] a) throws Exception {
    Database db = new DatabaseBuilder(new File(a[0])).setReadOnly(true).setIgnoreBrokenSystemCatalogIndex(true).setCodecProvider(new com.healthmarketscience.jackcess.crypt.CryptCodecProvider(a.length>2?a[2]:null)).open();
    int maxRows = Integer.parseInt(a[1]);
    System.out.println("FORMAT: "+db.getFileFormat()+" charset="+db.getCharset()+" pwd="+db.getDatabasePassword());
    System.out.println("DB PROPS: "+db.getDatabaseProperties());
    System.out.println("SUMMARY PROPS: "+db.getSummaryProperties());
    for (String t : db.getTableNames()) {
      Table tb = db.getTable(t);
      System.out.println("\n=== TABLE "+t+" rows="+tb.getRowCount());
      try { System.out.println("  tprops: "+tb.getProperties()); } catch(Exception e){}
      for (Column c : tb.getColumns()) {
        System.out.println("  COL "+c.getName()+" : "+c.getType()+" len="+c.getLength()+(c.isAutoNumber()?" AUTONUM":"")+(c.isVariableLength()?" var":"")+" props="+c.getProperties());
      }
      for (Index i : tb.getIndexes()) {
        StringBuilder sb=new StringBuilder(); for (Index.Column ic: i.getColumns()) sb.append(ic.getName()).append(ic.isAscending()?"":" DESC").append(",");
        System.out.println("  IDX "+i.getName()+" ["+sb+"]"+(i.isPrimaryKey()?" PK":"")+(i.isUnique()?" UNIQUE":"")+(i.isForeignKey()?" FK":"")+(i.shouldIgnoreNulls()?" ignoreNulls":"")+(i.isRequired()?" required":""));
      }
      int n=0;
      if (a.length>3 && !t.equals(a[3])) continue; for (Row r : tb) { if (n++>=maxRows) break; System.out.println("  ROW "+r); }
    }
    System.out.println("\n=== RELATIONSHIPS");
    for (Relationship r : db.getRelationships()) System.out.println("  "+r.getName()+": "+r.getFromTable().getName()+r.getFromColumns().stream().map(Column::getName).toList()+" -> "+r.getToTable().getName()+r.getToColumns().stream().map(Column::getName).toList()+" enforced="+!r.hasReferentialIntegrity()+" RI="+r.hasReferentialIntegrity()+" cascUpd="+r.cascadeUpdates()+" cascDel="+r.cascadeDeletes()+" oneToOne="+r.isOneToOne());
    System.out.println("\n=== QUERIES");
    for (Query q : db.getQueries()) { System.out.println("  Q "+q.getName()+" ["+q.getType()+"]:\n    "+q.toSQLString().replace("\n","\n    ")); }
    System.out.println("\n=== SYSTEM TABLES");
    for (String t : db.getSystemTableNames()) System.out.println("  "+t);
  }
}
