package som.interpreter.nodes.dispatch;

import som.interpreter.SArguments;
import som.interpreter.nodes.dispatch.AbstractDispatchNode.AbstractCachedDispatchNode;
import som.vm.Universe;
import som.vmobjects.SArray;
import som.vmobjects.SClass;
import som.vmobjects.SObject;
import som.vmobjects.SSymbol;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;


public final class CachedDnuSObjectCheckNode extends AbstractCachedDispatchNode {
  private final SClass expectedClass;
  private final SSymbol selector;

  public CachedDnuSObjectCheckNode(final SClass rcvrClass,
      final SSymbol selector, final Universe universe,
      final AbstractDispatchNode nextInCache) {
    super(rcvrClass.lookupInvokable(
        universe.symbolFor("doesNotUnderstand:arguments:")).
        getUnenforcedCallTarget(),
      nextInCache);
    expectedClass = rcvrClass;
    this.selector = selector;
  }

  @Override
  public Object executeDispatch(final VirtualFrame frame, final SObject domain,
      final boolean enforced, final Object[] arguments) {
    SObject rcvr = CompilerDirectives.unsafeCast(arguments[0], SObject.class, true);

    if (rcvr.getSOMClass(null) == expectedClass) {
      Object[] argsArr = SArguments.createSArgumentsArray(enforced, domain,
          rcvr, selector, SArray.fromArgArrayWithReceiverToSArrayWithoutReceiver(
              arguments, domain));
      return cachedMethod.call(frame, argsArr);
    } else {
      return nextInCache.executeDispatch(frame, domain, enforced, arguments);
    }
  }
}
