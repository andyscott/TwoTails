package twotails

import org.scalatest.{ FlatSpec, Matchers }

class SpeedBump{
  @mutualrec final def one(x: Int, y: Int = 1): Int = if(0 < x) two(x-1) else y
  @mutualrec final def two(x: Int, y: Int = 2): Int = if(0 < x) one(x-1) else y
}

class Pothole{
  @mutualrec final def one(x: Int)(y: Int, z: Int = 1): Int = if(0 < x) two(x-1)(y) else z
  @mutualrec final def two(x: Int)(y: Int, z: Int = 2): Int = if(0 < x) one(x-1)(y) else z
}

class Ditch{
  @mutualrec final def one(x: Int, y: Int, z: Int = 1): Int = if(0 < x) two(x = x-1, y = y) else z
  @mutualrec final def two(x: Int, y: Int, z: Int = 2): Int = if(0 < x) one(y = y, x = x-1) else z
}

class DefaultArgumentTest extends FlatSpec with Matchers{
  //
}