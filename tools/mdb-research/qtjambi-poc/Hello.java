import io.qt.widgets.*; import io.qt.core.*; import io.github.spannm.jackcess.*; import java.io.File;
public class Hello {
  public static void main(String[] args) throws Exception {
    QApplication.initialize(args);
    QMainWindow w = new QMainWindow(); w.setWindowTitle("yCash prototype (QtJambi + Jackcess)");
    QTableWidget t = new QTableWidget(); w.setCentralWidget(t);
    try (Database db = DatabaseBuilder.open(new File(args[0]))) {
      Table dd = db.getTable("DvizhenieDeneg");
      t.setColumnCount(4); t.setHorizontalHeaderLabels(java.util.List.of("id","Дата","Сумма","Комментарий"));
      int r=0; for (Row row : dd) { if (Boolean.TRUE.equals(row.get("del"))) continue; t.setRowCount(r+1);
        t.setItem(r,0,new QTableWidgetItem(""+row.get("id"))); t.setItem(r,1,new QTableWidgetItem(""+row.get("Date")));
        t.setItem(r,2,new QTableWidgetItem(""+row.get("Summa"))); t.setItem(r,3,new QTableWidgetItem(""+row.get("Comment"))); r++; }
    }
    QDockWidget dock = new QDockWidget("Фильтр", w); dock.setWidget(new QLabel("докируемая панель")); w.addDockWidget(Qt.DockWidgetArea.TopDockWidgetArea, dock);
    w.resize(800,400); w.show();
    QTimer.singleShot(4000, () -> { w.grab().save(args[1]); QApplication.quit(); });
    QApplication.exec(); QApplication.shutdown();
  }
}
