import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { BranchMap } from "../components/BranchMap";
import type { BranchMapBranch } from "../components/BranchMap";

const mockBranches: BranchMapBranch[] = [
  { label: "escalation", probability: 0.4, evidence: ["Signal A", "Signal B"] },
  { label: "de_escalation", probability: 0.35, evidence: ["Signal C"] },
  { label: "status_quo", probability: 0.25, evidence: ["Signal D"] },
];

describe("BranchMap", () => {
  it("renders without crashing with minimal branches", () => {
    const branches: BranchMapBranch[] = [
      { label: "branch_a", probability: 0.6 },
      { label: "branch_b", probability: 0.4 },
    ];
    render(<BranchMap branches={branches} />);
    const map = screen.getByTestId("branch-map");
    expect(map).toBeInTheDocument();
  });

  it("renders a REDACTED_SECRET REDACTED_SECRET", () => {
    render(<BranchMap branches={mockBranches} />);
    const REDACTED_SECRET = screen.getByTestId("branch-REDACTED_SECRET");
    expect(REDACTED_SECRET).toBeInTheDocument();
  });

  it("renders correct number of branch REDACTED_SECRETs", () => {
    render(<BranchMap branches={mockBranches} />);
    for (let i = 0; i < mockBranches.length; i++) {
      expect(screen.getByTestId(`branch-REDACTED_SECRET-${i}`)).toBeInTheDocument();
    }
  });

  it("displays probability labels for each branch", () => {
    render(<BranchMap branches={mockBranches} />);
    expect(screen.getByTestId("branch-prob-0").textContent).toBe("40%");
    expect(screen.getByTestId("branch-prob-1").textContent).toBe("35%");
    expect(screen.getByTestId("branch-prob-2").textContent).toBe("25%");
  });

  it("displays branch label text", () => {
    render(<BranchMap branches={mockBranches} />);
    expect(screen.getByTestId("branch-label-0").textContent).toBe("escalation");
    // Underscores replaced with spaces
    expect(screen.getByTestId("branch-label-1").textContent).toBe("de escalation");
  });

  it("renders connecting lines between REDACTED_SECRET and branch REDACTED_SECRETs", () => {
    render(<BranchMap branches={mockBranches} />);
    for (let i = 0; i < mockBranches.length; i++) {
      const line = screen.getByTestId(`branch-line-${i}`);
      expect(line).toBeInTheDocument();
      expect(line.tagName.toLowerCase()).toBe("path");
    }
  });

  it("handles 4 branches (max)", () => {
    const fourBranches: BranchMapBranch[] = [
      { label: "a", probability: 0.3 },
      { label: "b", probability: 0.3 },
      { label: "c", probability: 0.2 },
      { label: "d", probability: 0.2 },
    ];
    render(<BranchMap branches={fourBranches} />);
    expect(screen.getByTestId("branch-REDACTED_SECRET-3")).toBeInTheDocument();
  });

  it("uses custom REDACTED_SECRET label when provided", () => {
    render(<BranchMap branches={mockBranches} REDACTED_SECRETLabel="Geopolitical Outlook" />);
    const svg = screen.getByRole("img");
    // The REDACTED_SECRET label text REDACTED_SECRET should be in the SVG
    const texts = svg.querySelectorAll("text");
    const REDACTED_SECRETText = Array.from(texts).find((t) => t.textContent === "Geopolitical Outlook");
    expect(REDACTED_SECRETText).toBeTruthy();
  });

  it("sets correct aria-label with branch count", () => {
    render(<BranchMap branches={mockBranches} />);
    const svg = screen.getByRole("img");
    expect(svg.getAttribute("aria-label")).toBe("Narrative branch map with 3 branches");
  });
});
