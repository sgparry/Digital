/*
 * Copyright (c) 2018 Helmut Neemann.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.hdl.model2;

import de.neemann.digital.core.NodeException;
import de.neemann.digital.core.basic.Not;
import de.neemann.digital.core.element.ElementAttributes;
import de.neemann.digital.core.element.Keys;
import de.neemann.digital.core.io.In;
import de.neemann.digital.core.io.Out;
import de.neemann.digital.core.io.PowerSupply;
import de.neemann.digital.core.io.Probe;
import de.neemann.digital.core.pld.PullDown;
import de.neemann.digital.core.pld.PullUp;
import de.neemann.digital.core.wiring.Break;
import de.neemann.digital.core.wiring.Clock;
import de.neemann.digital.draw.elements.*;
import de.neemann.digital.draw.model.InverterConfig;
import de.neemann.digital.draw.model.Net;
import de.neemann.digital.draw.model.NetList;
import de.neemann.digital.gui.components.data.DummyElement;
import de.neemann.digital.hdl.model2.expression.ExprNot;
import de.neemann.digital.hdl.model2.expression.ExprVar;
import de.neemann.digital.hdl.printer.CodePrinter;
import de.neemann.digital.testing.TestCaseElement;

import java.io.IOException;
import java.util.*;

/**
 * The representation of a circuit
 */
public class HDLCircuit implements Iterable<HDLNode>, HDLContext.BitProvider, Printable {
    private final String elementName;
    private final ArrayList<HDLPort> outputs;
    private final ArrayList<HDLPort> inputs;
    private final ArrayList<HDLNet> listOfNets;
    private NetList netList;
    private ArrayList<HDLNode> nodes;
    private HashMap<Net, HDLNet> nets;

    /**
     * Creates a new instance
     *
     * @param circuit     the circuit
     * @param elementName the name of the circuit
     * @param c           the context to create the circuits
     * @throws PinException  PinException
     * @throws HDLException  HDLException
     * @throws NodeException NodeException
     */
    public HDLCircuit(Circuit circuit, String elementName, HDLContext c) throws PinException, HDLException, NodeException {
        this.elementName = elementName;
        inputs = new ArrayList<>();
        outputs = new ArrayList<>();

        nodes = new ArrayList<>();
        nets = new HashMap<>();
        listOfNets = new ArrayList<>();
        netList = new NetList(circuit);
        try {
            for (VisualElement v : circuit.getElements()) {
                if (v.equalsDescription(In.DESCRIPTION) || v.equalsDescription(Clock.DESCRIPTION))
                    addInput(new HDLPort(
                            v.getElementAttributes().getCleanLabel(),
                            getNetOfPin(v.getPins().get(0)),
                            HDLPort.Direction.OUT,
                            v.getElementAttributes().getBits())
                            .setPinNumber(v.getElementAttributes().get(Keys.PINNUMBER)));
                else if (v.equalsDescription(Out.DESCRIPTION))
                    addOutput(new HDLPort(
                            v.getElementAttributes().getCleanLabel(),
                            getNetOfPin(v.getPins().get(0)),
                            HDLPort.Direction.IN,
                            v.getElementAttributes().getBits())
                            .setPinNumber(v.getElementAttributes().get(Keys.PINNUMBER)));
                else if (isRealElement(v))
                    nodes.add(c.createNode(v, this));
            }
        } catch (HDLException e) {
            throw new HDLException("error parsing " + circuit.getOrigin(), e);
        }

        netList = null;
        nets = null;

        for (HDLNet n : listOfNets)
            n.fixBits();

        // fix inverted inputs
        ArrayList<HDLNode> newNodes = new ArrayList<>();
        for (HDLNode n : nodes) {
            InverterConfig iv = n.getElementAttributes().get(Keys.INVERTER_CONFIG);
            if (!iv.isEmpty()) {
                for (HDLPort p : n.getInputs())
                    if (iv.contains(p.getName()))
                        newNodes.add(createNot(p, n));
            }
        }
        nodes.addAll(newNodes);

        for (HDLPort i : inputs)
            i.getNet().setIsInput(i.getName());

        for (HDLPort o : outputs)
            if (o.getNet().needsVariable())
                o.getNet().setIsOutput(o.getName(), o.getNet().getInputs().size() == 1);

    }

    private HDLNode createNot(HDLPort p, HDLNode node) throws HDLException, NodeException, PinException {
        final ElementAttributes attr = new ElementAttributes().setBits(p.getBits());
        HDLNodeExpression n = new HDLNodeExpression(Not.DESCRIPTION.getName(), attr, name -> p.getBits());
        HDLNet outNet = new HDLNet(null);
        listOfNets.add(outNet);
        HDLNet inNet = p.getNet();
        inNet.remove(p);

        n.addInput(new HDLPort(Not.DESCRIPTION.getInputDescription(attr).get(0).getName(), inNet, HDLPort.Direction.IN, p.getBits()));
        n.addOutput(new HDLPort(Not.DESCRIPTION.getOutputDescriptions(attr).get(0).getName(), outNet, HDLPort.Direction.OUT, p.getBits()));

        p.setNet(outNet);
        node.replaceNet(inNet, outNet);

        n.setExpression(new ExprNot(new ExprVar(n.getInputs().get(0).getNet())));

        return n;
    }

    private void addOutput(HDLPort port) {
        outputs.add(port);
    }

    private void addInput(HDLPort port) {
        inputs.add(port);
    }

    private boolean isRealElement(VisualElement v) {
        return !v.equalsDescription(Tunnel.DESCRIPTION)
                && !v.equalsDescription(Break.DESCRIPTION)
                && !v.equalsDescription(PullDown.DESCRIPTION)
                && !v.equalsDescription(PullUp.DESCRIPTION)
                && !v.equalsDescription(Probe.DESCRIPTION)
                && !v.equalsDescription(PowerSupply.DESCRIPTION)
                && !v.equalsDescription(DummyElement.TEXTDESCRIPTION)
                && !v.equalsDescription(DummyElement.DATADESCRIPTION)
                && !v.equalsDescription(TestCaseElement.TESTCASEDESCRIPTION);
    }

    HDLNet getNetOfPin(Pin pin) {
        Net n = netList.getNetOfPos(pin.getPos());
        if (n == null)
            return null;

        return nets.computeIfAbsent(n, net -> {
            final HDLNet hdlNet = new HDLNet(createNetName(net));
            listOfNets.add(hdlNet);
            return hdlNet;
        });
    }

    private String createNetName(Net net) {
        final HashSet<String> labels = net.getLabels();
        if (labels.size() == 1)
            return labels.iterator().next();
        else
            return null;
    }

    @Override
    public Iterator<HDLNode> iterator() {
        return nodes.iterator();
    }

    @Override
    public int getBits(String name) {
        for (HDLPort o : outputs)
            if (o.getName().equals(name))
                return o.getBits();
        return 0;
    }

    /**
     * @return the elements name
     */
    public String getElementName() {
        return elementName;
    }

    /**
     * @return the circuits outputs
     */
    public ArrayList<HDLPort> getOutputs() {
        return outputs;
    }

    /**
     * @return the circuits inputs
     */
    public ArrayList<HDLPort> getInputs() {
        return inputs;
    }

    /**
     * Traverses all the nodes
     *
     * @param visitor the visitor to use
     */
    public void traverse(HDLVisitor visitor) {
        for (HDLNode n : nodes)
            n.traverse(visitor);
    }

    @Override
    public String toString() {
        return "HDLCircuit{elementName='" + elementName + "'}";
    }

    /**
     * Merges logcal operations if possible
     *
     * @return this for chained calls
     * @throws HDLException HDLException
     */
    public HDLCircuit mergeOperations() throws HDLException {
        nodes = new OperationMerger(nodes, this).merge();
        return this;
    }

    /**
     * Name the unnamed nets
     *
     * @param netNamer the net naming algorithm
     * @return this for chained calls
     */
    public HDLCircuit nameNets(NetNamer netNamer) {
        for (HDLNet n : listOfNets)
            if (n.getName() == null)
                n.setName(netNamer.createName(n));
        return this;
    }


    @Override
    public void print(CodePrinter out) throws IOException {
        out.print("circuit ").println(elementName).inc();
        out.print("in");
        printList(out, inputs);
        out.print("out");
        printList(out, outputs);
        out.print("sig");
        printList(out, listOfNets);

        out.println();
        for (HDLNode n : nodes) {
            out.print("node ").println(n.getElementName()).inc();
            n.print(out);
            out.dec();
        }
        out.println();
        for (HDLPort p : outputs) {
            final HDLNet net = p.getNet();
            if (net.needsVariable() || net.isInput()) {
                p.print(out);
                out.print(" := ");
                net.print(out);
                out.println();
            }
        }

        out.dec().print("end circuit ").println(elementName);
    }

    private void printList(CodePrinter out, Collection<? extends Printable> ports) throws IOException {
        boolean first = true;
        for (Printable p : ports) {
            if (first) {
                first = false;
                out.print("(");
            } else
                out.print(", ");
            p.print(out);
        }
        if (first)
            out.print("(");
        out.println(")");
    }

    private void printList(CodePrinter out, ArrayList<HDLNet> nets) throws IOException {
        boolean first = true;
        for (HDLNet net : nets) {
            if (net.needsVariable()) {
                if (first) {
                    first = false;
                    out.print("(");
                } else
                    out.print(", ");
                net.print(out);
            }
        }
        if (first)
            out.print("(");
        out.println(")");
    }

    /**
     * Removed an obsolete net
     *
     * @param net the net to remove
     */
    public void removeNet(HDLNet net) {
        listOfNets.remove(net);
    }

    /**
     * The net naming algorithm
     */
    public interface NetNamer {
        /**
         * Returns a nem for the given net
         *
         * @param n the net to name
         * @return the name to use
         */
        String createName(HDLNet n);
    }

    /**
     * Simple naming algorithm. Numbers all nets beginning with zero.
     */
    public static class SimpleNaming implements NetNamer {
        private int num = 0;

        @Override
        public String createName(HDLNet n) {
            return "s" + (num++);
        }
    }
}