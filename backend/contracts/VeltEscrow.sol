// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

interface IERC20 {
    function transfer(address to, uint256 amount) external returns (bool);
    function transferFrom(address from, address to, uint256 amount) external returns (bool);
}

/// Conditional USDC escrow for Velt payments: the payer's smart account holds funds
/// here, and they are released to the merchant either by the backend operator
/// (merchant confirmed delivery) or by anyone once the release delay has elapsed.
contract VeltEscrow {
    enum Status {
        None,
        Held,
        Released,
        Refunded
    }

    struct Hold {
        address payer;
        address merchant;
        uint256 amount;
        uint64 releaseAfter;
        Status status;
    }

    IERC20 public immutable usdc;
    address public immutable operator;
    uint64 public immutable releaseDelaySeconds;

    mapping(bytes32 => Hold) public holds;

    event PaymentHeld(
        bytes32 indexed paymentId,
        address indexed payer,
        address indexed merchant,
        uint256 amount,
        uint64 releaseAfter
    );
    event PaymentReleased(bytes32 indexed paymentId, address indexed merchant, uint256 amount, address releasedBy);
    event PaymentRefunded(bytes32 indexed paymentId, address indexed payer, uint256 amount);

    error AlreadyHeld();
    error NotHeld();
    error NotReleasableYet();
    error NotOperator();
    error TransferFailed();

    constructor(address usdc_, address operator_, uint64 releaseDelaySeconds_) {
        usdc = IERC20(usdc_);
        operator = operator_;
        releaseDelaySeconds = releaseDelaySeconds_;
    }

    /// msg.sender is the payer's smart account; it must have approved this contract first.
    function hold(bytes32 paymentId, address merchant, uint256 amount) external {
        if (holds[paymentId].status != Status.None) revert AlreadyHeld();

        uint64 releaseAfter = uint64(block.timestamp) + releaseDelaySeconds;
        holds[paymentId] = Hold(msg.sender, merchant, amount, releaseAfter, Status.Held);

        if (!usdc.transferFrom(msg.sender, address(this), amount)) revert TransferFailed();

        emit PaymentHeld(paymentId, msg.sender, merchant, amount, releaseAfter);
    }

    /// Operator may release at any time; anyone may release after the timeout (automatic release).
    function release(bytes32 paymentId) external {
        Hold storage h = holds[paymentId];
        if (h.status != Status.Held) revert NotHeld();
        if (msg.sender != operator && block.timestamp < h.releaseAfter) revert NotReleasableYet();

        h.status = Status.Released;
        if (!usdc.transfer(h.merchant, h.amount)) revert TransferFailed();

        emit PaymentReleased(paymentId, h.merchant, h.amount, msg.sender);
    }

    function refund(bytes32 paymentId) external {
        if (msg.sender != operator) revert NotOperator();
        Hold storage h = holds[paymentId];
        if (h.status != Status.Held) revert NotHeld();

        h.status = Status.Refunded;
        if (!usdc.transfer(h.payer, h.amount)) revert TransferFailed();

        emit PaymentRefunded(paymentId, h.payer, h.amount);
    }
}
