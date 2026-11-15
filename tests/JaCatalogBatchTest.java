package com.example.ja;
public final class JaCatalogBatchTest {
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args){
  JaCatalogBatch b=new JaCatalogBatch(0,1,5);
  for(int page=1;page<=5;page++){check(b.hasNext()&&b.getNextPage()==page);b.accept(page,30);}
  check(!b.hasNext()&&b.getLastPage()==5);
  b=new JaCatalogBatch(5,30,5);check(b.getNextPage()==6);
  // A failed fetch never calls accept; retry retains exactly the failed page.
  check(b.getNextPage()==6);b.accept(6,30);check(b.getNextPage()==7);
  try{b.accept(8,30);throw new AssertionError();}catch(IllegalArgumentException expected){}
  check(b.getNextPage()==7);
  // Filtered-empty pages still advance once successfully parsed.
  b.accept(7,30);check(b.getNextPage()==8);
  b=new JaCatalogBatch(498,800,5);b.accept(499,800);b.accept(500,800);
  check(!b.hasNext()&&b.getNextPage()==501&&b.getTotalPages()==500);
  b=new JaCatalogBatch(0,1,5);b.accept(1,1);check(!b.hasNext());
  b=new JaCatalogBatch(9,100,1);b.accept(10,100);check(!b.hasNext()&&b.getNextPage()==11);
  // Retain the tail of the final initial page before continuing the cursor.
  JaGenreCatalog<Integer> row=new JaGenreCatalog<>(new int[]{1},Integer.MAX_VALUE);
  for(int id=1;id<=60;id++)row.add(1,id,id);
  b=new JaCatalogBatch(3,20,1);check(b.getNextPage()==4);
  for(int id=61;id<=80;id++)row.add(1,id,id);
  b.accept(4,20);check(row.titles(1).size()==80&&row.titles(1).get(50)==51&&b.getNextPage()==5);
  System.out.println("PASS: five-page batches, dynamic totals, retries, filtered pages, exact cursors and 500-page cap");
 }
}
