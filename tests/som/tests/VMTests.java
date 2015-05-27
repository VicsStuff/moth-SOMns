package som.tests;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import som.VM;
import som.VM.Options;


public class VMTests {

  @Test
  public void testProcessArgumentsNothing() {
    Options opts = (new VM(true)).processVmArguments(new String[0]);
    assertEquals(opts.platformFile, VM.standardPlatformFile);
    assertNull(opts.args);
  }

  @Test
  public void testProcessArgumentsWithPlatformFile() {
    Options opts = (new VM(true)).processVmArguments(
        new String[] {"--platform", "foo.som"});
    assertEquals(opts.platformFile, "foo.som");
    assertNull(opts.args);
  }

  @Test
  public void testProcessArgumentsWithKernelFile() {
    Options opts = (new VM(true)).processVmArguments(
        new String[] {"--kernel", "foo.som"});
    assertEquals(opts.kernelFile, "foo.som");
    assertNull(opts.args);
  }

  @Test
  public void testProcessArgumentsWithAppFile() {
    Options opts = (new VM(true)).processVmArguments(
        new String[] {"app.som"});
    assertEquals(opts.platformFile, VM.standardPlatformFile);
    assertArrayEquals(opts.args, new String[] {"app.som"});
  }

  @Test
  public void testProcessArgumentsWithAppFileAndArgs() {
    Options opts = (new VM(true)).processVmArguments(
        new String[] {"app.som", "Foo", "1", "2"});
    assertEquals(opts.platformFile, VM.standardPlatformFile);
    assertArrayEquals(opts.args, new String[] {"app.som", "Foo", "1", "2"});
  }
}
