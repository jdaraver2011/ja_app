package com.example.ja;
/** Cursor advances only after a successfully parsed page; filtered-empty pages still count. */
public final class JaCatalogBatch {
 private int lastPage,totalPages,accepted;
 private final int limit;
 public JaCatalogBatch(int lastPage,int totalPages,int limit){
  if(lastPage<0||lastPage>500||limit<1)throw new IllegalArgumentException("Invalid batch");
  this.lastPage=lastPage;this.totalPages=Math.max(1,Math.min(500,totalPages));this.limit=limit;
 }
 public boolean hasNext(){return accepted<limit&&lastPage<totalPages&&lastPage<500;}
 public int getNextPage(){return lastPage+1;}
 public int getLastPage(){return lastPage;}
 public int getTotalPages(){return totalPages;}
 public void accept(int page,int total){
  if(!hasNext()||page!=lastPage+1)throw new IllegalArgumentException("Unexpected page");
  lastPage=page;totalPages=Math.max(1,Math.min(500,total));accepted++;
 }
}
